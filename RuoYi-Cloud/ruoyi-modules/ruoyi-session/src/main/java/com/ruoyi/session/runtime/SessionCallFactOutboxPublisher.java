package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Delivers only committed, whitelisted C5 facts; an unavailable system leaves the local event retryable. */
@Component
public class SessionCallFactOutboxPublisher
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SessionCallFactOutboxPublisher(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json)
    { this.jdbc = jdbc; this.transactions = transactions; this.json = json; }

    @Scheduled(fixedDelayString = "${platform.runtime.call-fact-publisher-delay-ms:5000}")
    public void publishOne()
    {
        Event event = transactions.execute(status -> {
            Event candidate = jdbc.query("select id,event_id,payload from s_outbox where event_type='CALL_FACT_RECORDED' and status in ('PENDING','SENDING') and (next_run_at is null or next_run_at<=utc_timestamp(3)) order by created_at,id limit 1 for update",
                rs -> rs.next() ? new Event(rs.getLong(1), rs.getString(2), rs.getString(3)) : null);
            if (candidate != null) jdbc.update("update s_outbox set status='SENDING',lease_expires_at=date_add(utc_timestamp(3),interval 30 second),attempt_count=attempt_count+1,updated_at=utc_timestamp(3) where id=?", candidate.id());
            return candidate;
        });
        if (event == null) return;
        try
        {
            String base = System.getenv("LN_SESSION_TO_SYSTEM_URL"), bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
            if (blank(base) || blank(bearer)) throw new IllegalStateException("system call-fact ingress is not configured");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/call-records/events")).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(event.payload())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("call-fact ingress rejected event");
            jdbc.update("update s_outbox set status='SENT',published_at=utc_timestamp(3),lease_expires_at=null,updated_at=utc_timestamp(3) where id=? and status='SENDING'", event.id());
        }
        catch (Exception ignored)
        {
            jdbc.update("update s_outbox set status='PENDING',lease_expires_at=null,next_run_at=date_add(utc_timestamp(3),interval least(300,5*pow(2,least(5,attempt_count))) second),updated_at=utc_timestamp(3) where id=? and status='SENDING'", event.id());
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record Event(long id, String eventId, String payload) { }
}
