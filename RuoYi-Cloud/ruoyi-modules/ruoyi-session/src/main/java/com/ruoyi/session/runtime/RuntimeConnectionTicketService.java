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

/** One-use, 30-second tickets bind an exact persisted grant and (for reauthorization) connection epoch. */
@Service
public class RuntimeConnectionTicketService
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final RuntimeAuthorization access;

    public RuntimeConnectionTicketService(JdbcTemplate jdbc, RuntimeAuthorization access)
    { this.jdbc = jdbc; this.access = access; }

    @Transactional
    public IssuedTicket issue(RuntimeAuthorization.Grant grant, String purpose, Long connectionEpoch)
    {
        if (!"CONNECT".equals(purpose) && !"REAUTHORIZE".equals(purpose))
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid ticket purpose.");
        if (("CONNECT".equals(purpose) && connectionEpoch != null)
            || ("REAUTHORIZE".equals(purpose) && (connectionEpoch == null || connectionEpoch <= 0)))
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid connection epoch.");
        RuntimePrincipal principal = access.verify(grant.id()).principal();
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant now = Instant.now();
        Instant expires = now.plus(30, ChronoUnit.SECONDS);
        VoiceRuntimeBinding voice = principal.voice();
        jdbc.update("insert into s_runtime_ticket (id,created_at,updated_at,ticket_hash,account_id,application_id,session_id," +
                "session_snapshot_id,voice_version_id,provider_kind,provider_voice_ref,official_service_id," +
                "official_service_revision,purpose,status,expires_at,grant_id,expected_connection_epoch) " +
                "values (uuid_short(),?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)",
            now, now, hash(ticket), principal.accountId(), principal.applicationId(), principal.sessionId(),
            principal.snapshotId(), voice.voiceVersionId(), voice.providerKind().name(), voice.providerVoiceRef(),
            voice.officialServiceId(), voice.officialServiceRevision(), purpose, expires,
            grant.id(), connectionEpoch);
        return new IssuedTicket(ticket, expires);
    }

    @Transactional
    public ConsumedTicket consume(String ticket)
    {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw rejected();
        TicketRow row = jdbc.query("select grant_id,account_id,application_id,session_id,session_snapshot_id,voice_version_id," +
                "purpose,expected_connection_epoch,status,expires_at from s_runtime_ticket where ticket_hash=? for update",
            rs -> rs.next() ? new TicketRow(rs.getObject(1) == null ? 0 : rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getObject(8) == null ? null : rs.getLong(8),
                rs.getString(9), rs.getTimestamp(10).toInstant()) : null, hash(ticket));
        if (row == null || row.grantId() <= 0 || !"ACTIVE".equals(row.status()) || !row.expiresAt().isAfter(Instant.now()))
            throw rejected();
        RuntimeAuthorization.Grant grant = access.verify(row.grantId());
        RuntimePrincipal principal = grant.principal();
        if (principal.accountId() != row.accountId() || principal.applicationId() != row.applicationId()
            || principal.sessionId() != row.sessionId() || principal.snapshotId() != row.snapshotId()
            || principal.voice().voiceVersionId() != row.voiceVersionId()) throw rejected();
        if (jdbc.update("update s_runtime_ticket set status='CONSUMED',consumed_at=utc_timestamp(3),updated_at=utc_timestamp(3) " +
            "where ticket_hash=? and status='ACTIVE'", hash(ticket)) != 1) throw rejected();
        return new ConsumedTicket(grant, row.purpose(), row.expectedEpoch());
    }

    private static RuntimeProblem rejected()
    { return new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TICKET_INVALID", "Connection ticket is invalid or expired."); }
    private static byte[] hash(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private record TicketRow(long grantId, long accountId, long applicationId, long sessionId, long snapshotId,
        long voiceVersionId, String purpose, Long expectedEpoch, String status, Instant expiresAt) { }
    public record IssuedTicket(String ticket, Instant expiresAt) { }
    public record ConsumedTicket(RuntimeAuthorization.Grant grant, String purpose, Long expectedEpoch) { }
}
