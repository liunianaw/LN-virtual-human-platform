package com.ruoyi.session.runtime;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists only DEBUG identity/grant metadata. S tokens themselves are signed separately and never stored. */
@Service
public class ConsoleDebugGrantService
{
    private final JdbcTemplate jdbcTemplate;
    private final RuntimeTokenCodec tokenCodec;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final ChatTurnStore chat;

    public ConsoleDebugGrantService(JdbcTemplate jdbcTemplate, RuntimeTokenCodec tokenCodec,
        com.fasterxml.jackson.databind.ObjectMapper json, ChatTurnStore chat)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenCodec = tokenCodec;
        this.json = json;
        this.chat = chat;
    }

    @Transactional
    public DebugSession create(CreateRequest request)
    {
        validateCreate(request);
        Long principalId = jdbcTemplate.query("select id from s_principal where account_id = ? and application_id = ? and principal_type = 'DEBUG' and external_user_id = ? for update",
                rs -> rs.next() ? rs.getLong(1) : null, request.accountId(), request.applicationId(), Long.toString(request.accountId()));
        Instant now = Instant.now();
        if (principalId == null)
        {
            principalId = nextId();
            jdbcTemplate.update("insert into s_principal (id,created_at,updated_at,account_id,application_id,principal_type,external_user_id,status,auth_epoch,last_seen_at) values (?,?,?,?,?,'DEBUG',?,'ACTIVE',1,?)",
                    principalId, now, now, request.accountId(), request.applicationId(), Long.toString(request.accountId()), now);
        }
        Long sessionId = jdbcTemplate.query("select id from s_session where account_id = ? and application_id = ? and principal_id = ? and create_request_id = ? for update",
                rs -> rs.next() ? rs.getLong(1) : null, request.accountId(), request.applicationId(), principalId, request.requestId());
        if (sessionId == null)
        {
            sessionId = nextId();
            jdbcTemplate.update("insert into s_session (id,created_at,updated_at,account_id,application_id,principal_id,app_config_id,create_request_id,reference_operation_id,status,auth_epoch,connection_epoch,next_turn_no,next_message_seq,last_activity_at,expires_at,revision) values (?,?,?,?,?,?,?,?,?,'ACTIVE',1,0,1,1,?,?,1)",
                    sessionId, now, now, request.accountId(), request.applicationId(), principalId, request.configVersionId(), request.requestId(),
                    "console-debug:" + request.requestId(), now, now.plus(30, ChronoUnit.DAYS));
        }
        return new DebugSession(sessionId, request.applicationId(), request.configVersionId());
    }

    @Transactional
    public IssuedToken mint(MintRequest request)
    {
        validateMint(request);
        SessionRow session = jdbcTemplate.query("select s.id,s.principal_id,s.auth_epoch,p.auth_epoch from s_session s join s_principal p on p.id = s.principal_id where s.id = ? and s.account_id = ? and s.application_id = ? and s.app_config_id = ? and s.status = 'ACTIVE' and p.principal_type = 'DEBUG' and p.status = 'ACTIVE' for update",
                rs -> rs.next() ? new SessionRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) : null,
                request.sessionId(), request.accountId(), request.applicationId(), request.configVersionId());
        if (session == null) throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT, "SESSION_NOT_READY", "The DEBUG Session is not active.");
        String tokenId = UUID.randomUUID().toString().replace("-", "");
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = request.expiresAt().isBefore(issuedAt.plus(15, ChronoUnit.MINUTES))
            ? request.expiresAt().truncatedTo(ChronoUnit.MILLIS) : issuedAt.plus(15, ChronoUnit.MINUTES);
        String binding;
        try { binding = json.writeValueAsString(request.voice()); }
        catch (Exception error) { throw new IllegalStateException(error); }
        String version = tokenCodec.currentKeyVersion();
        String scopes = "CHAT".equals(request.mode())
            ? "[\"session:read\",\"avatar:read\",\"speak:write\",\"chat:write\"]"
            : "[\"session:read\",\"avatar:read\",\"speak:write\"]";
        jdbcTemplate.update("insert into s_session_grant (id,created_at,updated_at,account_id,session_id,principal_id,application_id,grant_source,issuer_console_ref,token_id,scopes,account_epoch,application_epoch,principal_epoch,session_epoch,status,expires_at,signing_key_version,runtime_binding) values (?,?,?,?,?,?,?,'CONSOLE_DEBUG',unhex(?),?,cast(? as json),1,?,?,?, 'ACTIVE',?,?,cast(? as json))",
                nextId(), issuedAt, issuedAt, request.accountId(), session.sessionId(), session.principalId(), request.applicationId(),
                request.issuerConsoleRef(), tokenId, scopes, request.configVersionId(), session.principalEpoch(), session.sessionEpoch(), expiresAt, version, binding);
        RuntimeTokenCodec.V2Claims claims = new RuntimeTokenCodec.V2Claims(version, tokenId, request.accountId(),
                request.applicationId(), session.sessionId(), request.configVersionId(), "CONSOLE_DEBUG", issuedAt, expiresAt);
        return new IssuedToken(tokenCodec.encodeV2(claims), expiresAt);
    }

    @Transactional
    public void close(long accountId, long sessionId)
    {
        Instant now = Instant.now();
        Integer changed = jdbcTemplate.queryForObject("select count(1) from s_session where id = ? and account_id = ? and status = 'ACTIVE' for update",
            Integer.class, sessionId, accountId);
        if (changed == null || changed != 1) throw new RuntimeProblem(org.springframework.http.HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "DEBUG Session is unavailable.");
        chat.unknownInFlight(sessionId, "SESSION_REVOKED");
        jdbcTemplate.update("update s_turn set status='INTERRUPTED',text_status=if(text_status='RUNNING','INTERRUPTED',text_status)," +
            "cancel_reason='REVOKED',ended_at=coalesce(ended_at,?),updated_at=? where session_id=? and status='RUNNING'",
            now, now, sessionId);
        jdbcTemplate.update("update s_session_grant set status = 'REVOKED',updated_at = ? where session_id = ? and status = 'ACTIVE'", now, sessionId);
        jdbcTemplate.update("update s_session set status = 'DELETED',auth_epoch = auth_epoch + 1,active_turn_id = null,updated_at = ?,revision = revision + 1 where id = ?", now, sessionId);
    }

    private long nextId()
    {
        try
        {
            Long value = jdbcTemplate.queryForObject("select uuid_short()", Long.class);
            if (value == null || value <= 0) throw new IllegalStateException("Could not allocate a runtime identifier");
            return value;
        }
        catch (EmptyResultDataAccessException e) { throw new IllegalStateException("Could not allocate a runtime identifier", e); }
    }

    private static void validateCreate(CreateRequest request)
    {
        if (request == null || request.accountId() <= 0 || request.applicationId() <= 0 || request.configVersionId() <= 0
                || blank(request.requestId()) || request.requestId().length() > 64)
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid DEBUG Session request.");
    }

    private static void validateMint(MintRequest request)
    {
        if (request == null || request.accountId() <= 0 || request.applicationId() <= 0 || request.sessionId() <= 0
                || request.configVersionId() <= 0 || blank(request.issuerConsoleRef()) || request.issuerConsoleRef().length() != 64
                || request.expiresAt() == null || !request.expiresAt().isAfter(Instant.now()) || request.voice() == null
                || !java.util.Set.of("CHAT", "SPEAK_ONLY").contains(request.mode()))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid DEBUG Token request.");
        try { HexFormat.of().parseHex(request.issuerConsoleRef()); }
        catch (IllegalArgumentException e) { throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid console login reference."); }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record SessionRow(long sessionId, long principalId, long sessionEpoch, long principalEpoch) { }
    public record CreateRequest(long accountId, long applicationId, long configVersionId, String requestId) { }
    public record MintRequest(long accountId, long applicationId, long sessionId, long configVersionId, String issuerConsoleRef,
            Instant expiresAt, VoiceRuntimeBinding voice, String mode) { }
    public record DebugSession(long sessionId, long applicationId, long configVersionId) { }
    public record IssuedToken(String token, Instant expiresAt) { }
}
