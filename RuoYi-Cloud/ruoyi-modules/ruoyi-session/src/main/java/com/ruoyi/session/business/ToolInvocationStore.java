package com.ruoyi.session.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.runtime.RuntimeProblem;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Idempotency, per-Session limits, and redacted usage facts for HTTP Tools. */
@Repository
public class ToolInvocationStore
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ToolInvocationStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public Claim claim(long accountId, long sessionId, long skillId, String key, byte[] requestHash, int maximum)
    {
        Long locked = jdbc.query("select id from s_session where id=? and account_id=? and status='ACTIVE' " +
            "and expires_at>utc_timestamp(3) for update", rs -> rs.next() ? rs.getLong(1) : null, sessionId, accountId);
        if (locked == null) throw problem(HttpStatus.CONFLICT, "SESSION_NOT_ACTIVE");
        Claim prior = jdbc.query("select id,status,request_hash,result_json,error_code from s_tool_invocation " +
            "where session_id=? and skill_id=? and idempotency_key=? for update",
            rs -> rs.next() ? new Claim(rs.getLong(1), false, rs.getString(2), rs.getBytes(3),
                rs.getString(4), rs.getString(5)) : null, sessionId, skillId, key);
        if (prior != null)
        {
            if (!Arrays.equals(prior.requestHash(), requestHash))
                throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            if ("PROCESSING".equals(prior.status())) throw problem(HttpStatus.CONFLICT, "TOOL_IN_PROGRESS");
            return new Claim(prior.id(), true, prior.status(), prior.requestHash(), prior.resultJson(), prior.errorCode());
        }
        Integer calls = jdbc.queryForObject("select count(*) from s_tool_invocation where session_id=? and skill_id=?",
            Integer.class, sessionId, skillId);
        if (calls != null && calls >= maximum) throw problem(HttpStatus.TOO_MANY_REQUESTS, "TOOL_CALL_LIMIT");
        long id = nextId();
        Instant now = Instant.now();
        jdbc.update("insert into s_tool_invocation (id,created_at,updated_at,account_id,session_id,skill_id," +
            "idempotency_key,request_hash,status) values (?,?,?,?,?,?,?,?,'PROCESSING')",
            id, now, now, accountId, sessionId, skillId, key, requestHash);
        jdbc.update("insert into s_operation (id,created_at,updated_at,account_id,session_id,client_request_id," +
            "operation_type,ordinal,config_resource_id,status,input_hash,started_at) values " +
            "(?,?,?,?,?,?,'TOOL',0,?,'RUNNING',?,?)", id, now, now, accountId, sessionId,
            operationKey(skillId, key), skillId, requestHash, now);
        fact(accountId, sessionId, skillId, id, "STARTED", null);
        return new Claim(id, false, "PROCESSING", requestHash, null, null);
    }

    @Transactional
    public void succeed(long accountId, long sessionId, long skillId, long id, int status, String resultJson, int bytes)
    {
        Long active = jdbc.query("select id from s_session where id=? and account_id=? and status='ACTIVE' " +
            "and expires_at>utc_timestamp(3) for update", rs -> rs.next() ? rs.getLong(1) : null, sessionId, accountId);
        if (active == null) throw problem(HttpStatus.CONFLICT, "SESSION_NOT_ACTIVE");
        int changed = jdbc.update("update s_tool_invocation set status='SUCCEEDED',http_status=?,result_json=cast(? as json)," +
            "finished_at=utc_timestamp(3),updated_at=utc_timestamp(3) where id=? and account_id=? and status='PROCESSING'",
            status, resultJson, id, accountId);
        if (changed != 1) throw problem(HttpStatus.CONFLICT, "TOOL_STATE_CONFLICT");
        jdbc.update("update s_operation set status='SUCCEEDED',result_summary=json_object('resultBytes',?)," +
            "finished_at=utc_timestamp(3),updated_at=utc_timestamp(3) where id=? and session_id=? and status='RUNNING'",
            bytes, id, sessionId);
        fact(accountId, sessionId, skillId, id, "SUCCEEDED", null);
    }

    @Transactional
    public void fail(long accountId, long sessionId, long skillId, long id, String code, boolean submitted)
    {
        String status = submitted ? "UNKNOWN" : "FAILED";
        int changed = jdbc.update("update s_tool_invocation set status=?,error_code=?,finished_at=utc_timestamp(3)," +
            "updated_at=utc_timestamp(3) where id=? and account_id=? and status='PROCESSING'", status, code, id, accountId);
        if (changed == 0) return;
        jdbc.update("update s_operation set status=?,error_code=?,finished_at=utc_timestamp(3)," +
            "updated_at=utc_timestamp(3) where id=? and session_id=? and status='RUNNING'", status, code, id, sessionId);
        fact(accountId, sessionId, skillId, id, status, code);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    @Transactional
    public void reapUncertain()
    {
        var rows = jdbc.query("select id,account_id,session_id,skill_id from s_tool_invocation " +
            "where status='PROCESSING' and created_at<date_sub(utc_timestamp(3),interval 3 minute) limit 50 for update",
            (rs, index) -> new long[] {rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)});
        for (long[] row : rows) fail(row[1], row[2], row[3], row[0], "TOOL_OUTCOME_UNKNOWN", true);
    }

    private void fact(long accountId, long sessionId, long skillId, long operationId, String status, String code)
    {
        try
        {
            String eventId = operationId + ":" + status;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", eventId); payload.put("operationKey", Long.toString(operationId));
            payload.put("accountId", accountId); payload.put("sessionId", sessionId);
            payload.put("applicationId", jdbc.queryForObject("select application_id from s_session where id=?",
                Long.class, sessionId));
            payload.put("capability", "TOOL"); payload.put("skillId", skillId);
            payload.put("status", status); payload.put("errorCode", code);
            payload.put("usage", Map.of("usageAvailable", false)); payload.put("costSource", "UNKNOWN");
            jdbc.update("insert into s_outbox (id,created_at,updated_at,account_id,event_id,event_type,aggregate_type," +
                "aggregate_id,schema_version,trace_id,payload,status,attempt_count,next_run_at) values " +
                "(uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,1,?,cast(? as json),'PENDING',0,utc_timestamp(3))",
                accountId, eventId, "CALL_FACT_RECORDED", "TOOL_OPERATION", Long.toString(operationId), eventId,
                json.writeValueAsString(payload));
        }
        catch (Exception error) { throw new IllegalStateException("Tool call fact could not be persisted", error); }
    }

    private static String operationKey(long skillId, String key)
    {
        try
        {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                ("tool:" + skillId + ":" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private long nextId()
    {
        Long id = jdbc.queryForObject("select uuid_short()", Long.class);
        if (id == null || id <= 0) throw new IllegalStateException("Could not allocate Tool invocation ID");
        return id;
    }
    private static RuntimeProblem problem(HttpStatus status, String code)
    { return new RuntimeProblem(status, code, code); }
    public record Claim(long id, boolean replay, String status, byte[] requestHash, String resultJson, String errorCode) { }
}
