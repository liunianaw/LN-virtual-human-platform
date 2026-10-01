package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable redacted official ASR facts. No body, transcript, or capture is persisted. */
@Repository
public class RuntimeOperationStore
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public RuntimeOperationStore(JdbcTemplate jdbc, ObjectMapper json)
    { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public long beginAsr(RuntimePrincipal principal, String requestId, long officialServiceId, byte[] audio)
    {
        Long locked = jdbc.query("select id from s_session where id=? and account_id=? and status='ACTIVE' " +
            "and session_snapshot_id=? and expires_at>utc_timestamp(3) for update",
            rs -> rs.next() ? rs.getLong(1) : null, principal.sessionId(), principal.accountId(), principal.snapshotId());
        if (locked == null) throw problem("SESSION_NOT_ACTIVE");
        Integer existing = jdbc.queryForObject("select count(*) from s_operation where session_id=? and client_request_id=?",
            Integer.class, principal.sessionId(), requestId);
        if (existing != null && existing > 0) throw problem("REQUEST_ALREADY_USED");
        long operationId = id();
        jdbc.update("insert into s_operation (id,created_at,updated_at,account_id,session_id,client_request_id," +
            "operation_type,ordinal,config_resource_id,status,input_hash,started_at) " +
            "values (?,utc_timestamp(3),utc_timestamp(3),?,?,?,'ASR',0,?,'RUNNING',?,utc_timestamp(3))",
            operationId, principal.accountId(), principal.sessionId(), requestId, officialServiceId, sha256(audio));
        fact(principal, null, operationId, "ASR", "STARTED", null, null, null);
        return operationId;
    }

    @Transactional
    public void endAsr(RuntimePrincipal principal, long operationId, String status, String code,
        String providerRequestId, Long durationMs)
    {
        if (!java.util.Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(status)) throw problem("OPERATION_STATE_INVALID");
        int changed = jdbc.update("update s_operation set status=?,error_code=?,provider_request_id=?," +
            "result_summary=json_object('durationMs',?),finished_at=utc_timestamp(3),updated_at=utc_timestamp(3) " +
            "where id=? and account_id=? and session_id=? and operation_type='ASR' and status='RUNNING'",
            status, code, providerRequestId, durationMs, operationId, principal.accountId(), principal.sessionId());
        if (changed == 1) fact(principal, null, operationId, "ASR", status, code, providerRequestId, durationMs);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    @Transactional
    public void reapUncertain()
    {
        var rows = jdbc.query("select o.id,o.account_id,s.application_id,o.session_id,s.session_snapshot_id," +
            "snap.voice_version_id,snap.provider_voice_ref,snap.official_service_id,snap.official_service_revision " +
            "from s_operation o join s_session s on s.id=o.session_id " +
            "join s_session_snapshot snap on snap.id=s.session_snapshot_id " +
            "where o.operation_type='ASR' and o.status='RUNNING' " +
            "and o.started_at<date_sub(utc_timestamp(3),interval 3 minute) limit 50 for update",
            (rs, index) -> new java.util.AbstractMap.SimpleEntry<>(rs.getLong(1), new RuntimePrincipal(
                rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),java.util.Set.of(),
                new VoiceRuntimeBinding(rs.getLong(6),TtsProviderKind.OFFICIAL,rs.getString(7),rs.getLong(8),rs.getLong(9)))));
        for (var row : rows) endAsr(row.getValue(), row.getKey(), "UNKNOWN", "ASR_OUTCOME_UNKNOWN", null, null);
    }

    private void fact(RuntimePrincipal principal, Long turnId, long operationId, String type,
        String status, String code, String providerRequestId, Long audioDurationMs)
    {
        try
        {
            String eventId = operationId + ":" + status;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", eventId); payload.put("operationKey", Long.toString(operationId));
            payload.put("accountId", principal.accountId()); payload.put("applicationId", principal.applicationId());
            payload.put("sessionId", principal.sessionId()); payload.put("turnId", turnId);
            payload.put("capability", type); payload.put("status", status);
            payload.put("providerRequestId", providerRequestId); payload.put("errorCode", code);
            payload.put("usage", Map.of("usageAvailable", audioDurationMs != null,
                "audioDurationMs", audioDurationMs == null ? 0 : audioDurationMs));
            payload.put("costSource", "UNKNOWN");
            jdbc.update("insert into s_outbox (id,created_at,updated_at,account_id,event_id,event_type,aggregate_type," +
                "aggregate_id,schema_version,trace_id,payload,status,attempt_count,next_run_at) values " +
                "(uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,1,?,cast(? as json),'PENDING',0,utc_timestamp(3))",
                principal.accountId(), eventId, "CALL_FACT_RECORDED", type + "_OPERATION",
                Long.toString(operationId), eventId, json.writeValueAsString(payload));
        }
        catch (Exception error) { throw new IllegalStateException("Call fact could not be persisted", error); }
    }
    private long id()
    {
        Long value = jdbc.queryForObject("select uuid_short()", Long.class);
        if (value == null || value <= 0) throw new IllegalStateException("Could not allocate operation ID");
        return value;
    }
    private static byte[] digest(String value)
    { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
      catch (Exception error) { throw new IllegalStateException(error); } }
    private static byte[] sha256(byte[] value)
    { try { return MessageDigest.getInstance("SHA-256").digest(value); }
      catch (Exception error) { throw new IllegalStateException(error); } }
    private static RuntimeProblem problem(String code)
    { return new RuntimeProblem(HttpStatus.CONFLICT, code, code); }
}
