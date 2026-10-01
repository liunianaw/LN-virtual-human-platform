package com.ruoyi.system.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.developer.session.SessionLifecycleClient;

/** Delivers durable application disable events to active DEBUG sessions; retries until the inbox receipt is committed. */
@Component
public class ApplicationRevocationWorker
{
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationRevocationWorker.class);
    private static final String CONSUMER = "application-session-revoker";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SessionLifecycleClient sessions;

    public ApplicationRevocationWorker(JdbcTemplate jdbc, TransactionTemplate transactions, SessionLifecycleClient sessions)
    { this.jdbc = jdbc; this.transactions = transactions; this.sessions = sessions; }

    @Scheduled(fixedDelayString = "${platform.application.revocation-delay-ms:5000}")
    public void revokeDisabledApplicationSessions()
    {
        Event event = jdbc.query("select o.event_id,o.account_id,cast(o.aggregate_id as unsigned) from p_outbox o where o.event_type='APPLICATION_STATUS_CHANGED' and json_unquote(json_extract(o.payload,'$.status'))='DISABLED' and not exists (select 1 from p_inbox i where i.consumer_name=? and i.event_id=o.event_id) order by o.created_at,o.id limit 1",
            rs -> rs.next() ? new Event(rs.getString(1), rs.getLong(2), rs.getLong(3)) : null, CONSUMER);
        if (event == null) return;
        try
        {
            List<Long> ids = jdbc.query("select distinct r.holder_id from p_resource_reference r where r.account_id=? and r.holder_type='SESSION' and r.resource_type='APPLICATION_SNAPSHOT' and r.state in ('RESERVED','CONFIRMED') and r.resource_id=?", (rs, row) -> rs.getLong(1), event.accountId(), event.applicationId());
            for (Long sessionId : ids) sessions.close(event.accountId(), sessionId);
            jdbc.update("update p_resource_reference set state='RELEASED',released_at=utc_timestamp(3),updated_at=utc_timestamp(3) where account_id=? and holder_type='SESSION' and resource_type='APPLICATION_SNAPSHOT' and state in ('RESERVED','CONFIRMED') and resource_id=?", event.accountId(), event.applicationId());
            transactions.executeWithoutResult(status -> jdbc.update("insert ignore into p_inbox (id,created_at,updated_at,account_id,consumer_name,event_id,payload_hash,processed_at) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,unhex(sha2(?,256)),utc_timestamp(3))", event.accountId(), CONSUMER, event.eventId(), event.eventId()));
        }
        catch (Exception error) { LOG.warn("application disable still awaits session revocation: eventId={}, error={}", event.eventId(), error.getClass().getSimpleName()); }
    }
    private record Event(String eventId, long accountId, long applicationId) { }
}
