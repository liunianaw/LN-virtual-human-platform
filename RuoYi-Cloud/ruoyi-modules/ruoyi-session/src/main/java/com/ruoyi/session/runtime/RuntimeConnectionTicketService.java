package com.ruoyi.session.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues short-lived, one-time browser tickets without putting an S token in a WebSocket handshake. */
@Service
public class RuntimeConnectionTicketService
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public RuntimeConnectionTicketService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public IssuedTicket issue(RuntimePrincipal principal, String purpose)
    {
        if (!"CONNECT".equals(purpose) && !"REAUTHORIZE".equals(purpose))
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid connection ticket purpose.");
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        jdbc.update("insert into s_runtime_ticket (id,created_at,updated_at,ticket_hash,account_id,application_id,session_id,config_version_id,voice_version_id,provider_kind,provider_voice_ref,relay_version_ref,official_service_id,official_service_revision,purpose,status,expires_at) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)", nextId(), now, now, hash(ticket), principal.accountId(),
            principal.applicationId(), principal.sessionId(), principal.configVersionId(), principal.voice().voiceVersionId(),
            principal.voice().providerKind().name(), principal.voice().providerVoiceRef(), principal.voice().relayVersionRef(), principal.voice().officialServiceId(), principal.voice().officialServiceRevision(), purpose, now.plus(30, ChronoUnit.SECONDS));
        return new IssuedTicket(ticket, now.plus(30, ChronoUnit.SECONDS));
    }

    @Transactional
    public RuntimePrincipal consume(String ticket)
    {
        if (ticket == null || ticket.isBlank()) throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TICKET_INVALID", "Missing connection ticket.");
        TicketRow row = jdbc.query("select account_id,application_id,session_id,config_version_id,voice_version_id,provider_kind,provider_voice_ref,relay_version_ref,official_service_id,official_service_revision,status,expires_at from s_runtime_ticket where ticket_hash = ? for update",
            rs -> rs.next() ? new TicketRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9) == null ? null : rs.getLong(9), rs.getObject(10) == null ? null : rs.getLong(10), rs.getString(11), rs.getTimestamp(12).toInstant()) : null,
            hash(ticket));
        if (row == null || !"ACTIVE".equals(row.status()) || !row.expiresAt().isAfter(Instant.now()))
            throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TICKET_INVALID", "Connection ticket is expired or already used.");
        Integer activeGrant = jdbc.queryForObject("select count(1) from s_session_grant g join s_session s on s.id = g.session_id "
                + "where g.account_id = ? and g.application_id = ? and g.session_id = ? and s.app_config_id = ? "
                + "and g.grant_source = 'CONSOLE_DEBUG' and g.status = 'ACTIVE' and g.expires_at > utc_timestamp(3) and s.status = 'ACTIVE'",
            Integer.class, row.accountId(), row.applicationId(), row.sessionId(), row.configVersionId());
        if (activeGrant == null || activeGrant < 1) throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "The DEBUG Session Token is no longer valid.");
        RuntimePrincipal principal;
        try { principal = new RuntimePrincipal(row.accountId(), row.applicationId(), row.sessionId(), row.configVersionId(), java.util.Set.of("speak:write"),
            new VoiceRuntimeBinding(row.voiceVersionId(), TtsProviderKind.valueOf(row.providerKind()), row.providerVoiceRef(), row.relayVersionRef(), row.officialServiceId(), row.officialServiceRevision())); }
        catch (IllegalArgumentException e) { throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TICKET_INVALID", "Connection ticket is invalid."); }
        jdbc.update("update s_runtime_ticket set status = 'CONSUMED',consumed_at = ?,updated_at = ? where ticket_hash = ? and status = 'ACTIVE'",
            Instant.now(), Instant.now(), hash(ticket));
        return principal;
    }

    private long nextId()
    {
        Long id = jdbc.queryForObject("select uuid_short()", Long.class);
        if (id == null || id <= 0) throw new IllegalStateException("Could not allocate a runtime ticket identifier");
        return id;
    }
    private static byte[] hash(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private record TicketRow(long accountId, long applicationId, long sessionId, long configVersionId, long voiceVersionId,
        String providerKind, String providerVoiceRef, String relayVersionRef, Long officialServiceId, Long officialServiceRevision, String status, Instant expiresAt) { }
    public record IssuedTicket(String ticket, Instant expiresAt) { }
}
