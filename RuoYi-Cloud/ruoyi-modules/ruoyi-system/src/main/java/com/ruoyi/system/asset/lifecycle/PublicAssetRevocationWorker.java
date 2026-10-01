package com.ruoyi.system.asset.lifecycle;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.developer.session.SessionLifecycleClient;

/**
 * Local Outbox consumer for emergency disable.  An inbox row is written only
 * after every affected active session has acknowledged closure, so failed
 * delivery remains retryable without replaying a completed event.
 */
@Component
public class PublicAssetRevocationWorker
{
    private static final Logger LOG = LoggerFactory.getLogger(PublicAssetRevocationWorker.class);
    private static final String CONSUMER = "public-asset-session-revoker";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SessionLifecycleClient sessions;

    public PublicAssetRevocationWorker(JdbcTemplate jdbc, TransactionTemplate transactions, SessionLifecycleClient sessions)
    { this.jdbc = jdbc; this.transactions = transactions; this.sessions = sessions; }

    @Scheduled(fixedDelayString = "${platform.asset.lifecycle.revocation-delay-ms:5000}")
    public void revokeDisabledSessions()
    {
        Event event = jdbc.query("select o.event_id,o.account_id,o.event_type,o.aggregate_id from p_outbox o where o.event_type in ('AVATAR_STATUS_CHANGED','VOICE_STATUS_CHANGED') and json_unquote(json_extract(o.payload,'$.status'))='DISABLED' and not exists (select 1 from p_inbox i where i.consumer_name=? and i.event_id=o.event_id) order by o.created_at,o.id limit 1",
            rs -> rs.next() ? new Event(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4)) : null, CONSUMER);
        if (event == null) return;
        try
        {
            String kind = event.type().startsWith("AVATAR") ? "avatars" : "voices";
            String versionTable = "avatars".equals(kind) ? "p_avatar_version" : "p_voice_version";
            String ownerColumn = "avatars".equals(kind) ? "avatar_id" : "voice_id";
            String type = "avatars".equals(kind) ? "AVATAR_VERSION" : "VOICE_VERSION";
            List<SessionRef> active = jdbc.query("select distinct r.account_id,r.holder_id from p_resource_reference r join " + versionTable
                    + " v on v.id=r.resource_id where r.resource_type=? and v." + ownerColumn + "=? and r.holder_type='SESSION' and r.state in ('RESERVED','CONFIRMED')",
                (rs, row) -> new SessionRef(rs.getLong(1), rs.getLong(2)), type, event.resourceId());
            for (SessionRef session : active) sessions.close(session.accountId(), session.sessionId());
            transactions.executeWithoutResult(status -> jdbc.update("insert ignore into p_inbox (id,created_at,updated_at,account_id,consumer_name,event_id,payload_hash,processed_at) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,unhex(sha2(?,256)),utc_timestamp(3))",
                event.accountId(), CONSUMER, event.eventId(), event.eventId()));
        }
        catch (Exception error)
        {
            LOG.warn("public asset disable still awaits session revocation: eventId={}, error={}", event.eventId(), error.getClass().getSimpleName());
        }
    }

    private record Event(String eventId, long accountId, String type, long resourceId) { }
    private record SessionRef(long accountId, long sessionId) { }
}
