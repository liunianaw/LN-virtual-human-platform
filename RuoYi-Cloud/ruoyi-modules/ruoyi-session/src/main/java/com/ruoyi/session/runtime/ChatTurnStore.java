package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable redacted CHAT turns and per-call facts. No prompt, arguments or result body is persisted. */
@Repository
public class ChatTurnStore
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PersistentRuntimeStore runtime;
    public ChatTurnStore(JdbcTemplate jdbc, ObjectMapper json, PersistentRuntimeStore runtime)
    { this.jdbc = jdbc; this.json = json; this.runtime = runtime; }

    @Transactional
    public long openConnection(RuntimePrincipal principal)
    {
        long epoch = runtime.openConnection(principal);
        unknownInFlight(principal.sessionId(), "TURN_REPLACED");
        return epoch;
    }

    @Transactional
    public Started start(RuntimePrincipal principal, long epoch, String requestId, String text)
    {
        Session session = jdbc.query("select s.next_turn_no,s.active_turn_id,p.external_user_id from s_session s " +
            "join s_principal p on p.id=s.principal_id and p.principal_type in ('BUSINESS','DEBUG') " +
            "where s.id=? and s.account_id=? and s.application_id=? and s.app_config_id=? " +
            "and s.status='ACTIVE' and s.expires_at>utc_timestamp(3) and s.connection_epoch=? for update",
            rs -> rs.next() ? new Session(rs.getLong(1), rs.getObject(2) == null ? null : rs.getLong(2), rs.getString(3)) : null,
            principal.sessionId(), principal.accountId(), principal.applicationId(), principal.configVersionId(), epoch);
        if (session == null) throw problem("SESSION_NOT_READY");
        byte[] hash = digest(text);
        Existing existing = jdbc.query("select id,request_hash from s_turn where session_id=? and client_request_id=?",
            rs -> rs.next() ? new Existing(rs.getLong(1), rs.getBytes(2)) : null, principal.sessionId(), requestId);
        if (existing != null)
        {
            if (!MessageDigest.isEqual(existing.hash(), hash)) throw problem("REQUEST_CONFLICT");
            throw problem("REQUEST_ALREADY_USED");
        }
        if (session.activeTurnId() != null) interrupt(session.activeTurnId(), "REPLACED");
        long turnId = id();
        jdbc.update("insert into s_turn (id,created_at,updated_at,account_id,session_id,turn_no,client_request_id,request_hash," +
            "turn_type,status,text_status,audio_status,playback_status,connection_epoch,input_source,include_in_history,last_event_seq,started_at) " +
            "values (?,utc_timestamp(3),utc_timestamp(3),?,?,?,?,?, 'CHAT','RUNNING','RUNNING','NOT_REQUESTED','NOT_REQUESTED',?,'TEXT',0,0,utc_timestamp(3))",
            turnId, principal.accountId(), principal.sessionId(), session.nextTurnNo(), requestId, hash, epoch);
        jdbc.update("update s_session set next_turn_no=?,active_turn_id=?,last_activity_at=utc_timestamp(3)," +
            "updated_at=utc_timestamp(3),revision=revision+1 where id=?",
            session.nextTurnNo() + 1, turnId, principal.sessionId());
        return new Started(turnId, session.activeTurnId(), session.externalUserId());
    }

    @Transactional
    public long beginOperation(RuntimePrincipal principal, long turnId, String type, int ordinal, long resourceId, String inputHash)
    {
        if (!active(principal, turnId, currentEpoch(turnId))) throw problem("TURN_STOPPED");
        long operationId = id();
        String requestId = turnId + ":" + type.toLowerCase() + ":" + ordinal;
        jdbc.update("insert into s_operation (id,created_at,updated_at,account_id,session_id,turn_id,client_request_id," +
            "operation_type,ordinal,config_resource_id,status,input_hash,started_at) values (?,utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,?,?,'RUNNING',?,utc_timestamp(3))",
            operationId, principal.accountId(), principal.sessionId(), turnId, requestId,
            type, ordinal, resourceId, digest(inputHash));
        fact(principal, turnId, operationId, type, "STARTED", null, null, null, null);
        return operationId;
    }

    @Transactional
    public void endOperation(RuntimePrincipal principal, long turnId, long operationId, String type, String state,
        String code, String providerRequestId, Long inputTokens, Long outputTokens)
    {
        if (!java.util.Set.of("SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED").contains(state)) throw problem("OPERATION_STATE_INVALID");
        int changed = jdbc.update("update s_operation set status=?,error_code=?,provider_request_id=?,result_summary=" +
            "json_object('inputTokens',?,'outputTokens',?),finished_at=utc_timestamp(3),updated_at=utc_timestamp(3) " +
            "where id=? and account_id=? and session_id=? and turn_id=? and operation_type=? and status='RUNNING'",
            state, code, providerRequestId, inputTokens, outputTokens, operationId, principal.accountId(),
            principal.sessionId(), turnId, type);
        if (changed == 1) fact(principal, turnId, operationId, type, state, code, providerRequestId, inputTokens, outputTokens);
    }

    @Transactional
    public boolean stop(RuntimePrincipal principal, long turnId, String reason)
    {
        Long active = jdbc.query("select active_turn_id from s_session where id=? and account_id=? and application_id=? for update",
            rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null,
            principal.sessionId(), principal.accountId(), principal.applicationId());
        if (active == null || active != turnId) return false;
        interrupt(turnId, reason);
        return true;
    }

    @Transactional
    public boolean finish(RuntimePrincipal principal, long turnId, boolean success, String code)
    {
        int changed = jdbc.update("update s_turn set status=?,text_status=case when text_status='COMPLETED' " +
            "then 'COMPLETED' else ? end,audio_status=case when ?=0 and text_status='COMPLETED' then 'FAILED' " +
            "else audio_status end,error_code=?,ended_at=utc_timestamp(3)," +
            "updated_at=utc_timestamp(3) where id=? and account_id=? and session_id=? and status='RUNNING'",
            success ? "COMPLETED" : "FAILED", success ? "COMPLETED" : "FAILED", success ? 1 : 0, code,
            turnId, principal.accountId(), principal.sessionId());
        if (changed == 1)
            jdbc.update("update s_session set active_turn_id=null,updated_at=utc_timestamp(3),revision=revision+1 " +
                "where id=? and account_id=? and active_turn_id=?", principal.sessionId(), principal.accountId(), turnId);
        return changed == 1;
    }

    public boolean active(RuntimePrincipal principal, long turnId, long epoch)
    {
        Integer count = jdbc.queryForObject("select count(*) from s_session s join s_turn t on t.id=s.active_turn_id " +
            "where s.id=? and s.account_id=? and s.application_id=? and s.app_config_id=? and s.status='ACTIVE' " +
            "and s.expires_at>utc_timestamp(3) and s.connection_epoch=? and t.id=? and t.status='RUNNING'",
            Integer.class, principal.sessionId(), principal.accountId(), principal.applicationId(),
            principal.configVersionId(), epoch, turnId);
        return count != null && count == 1;
    }

    public long currentEpoch(long turnId)
    {
        Long epoch = jdbc.query("select connection_epoch from s_turn where id=?",
            rs -> rs.next() ? rs.getLong(1) : null, turnId);
        if (epoch == null) throw problem("TURN_NOT_FOUND");
        return epoch;
    }

    public boolean chatTurn(RuntimePrincipal principal, long turnId)
    {
        Integer count = jdbc.queryForObject("select count(*) from s_turn where id=? and account_id=? and session_id=? and turn_type='CHAT'",
            Integer.class, turnId, principal.accountId(), principal.sessionId());
        return count != null && count == 1;
    }

    public void textCompleted(RuntimePrincipal principal, long turnId)
    {
        jdbc.update("update s_turn set text_status='COMPLETED',updated_at=utc_timestamp(3) " +
            "where id=? and account_id=? and session_id=? and turn_type='CHAT' and status='RUNNING'",
            turnId, principal.accountId(), principal.sessionId());
    }

    public Long activeChat(RuntimePrincipal principal)
    {
        return jdbc.query("select t.id from s_session s join s_turn t on t.id=s.active_turn_id " +
            "where s.id=? and s.account_id=? and s.application_id=? and t.turn_type='CHAT' and t.status='RUNNING'",
            rs -> rs.next() ? rs.getLong(1) : null, principal.sessionId(), principal.accountId(), principal.applicationId());
    }

    public String externalUserId(RuntimePrincipal principal)
    {
        return jdbc.query("select p.external_user_id from s_session s join s_principal p on p.id=s.principal_id " +
            "where s.id=? and s.account_id=? and s.application_id=? and s.app_config_id=? and s.status='ACTIVE'",
            rs -> rs.next() ? rs.getString(1) : null, principal.sessionId(), principal.accountId(),
            principal.applicationId(), principal.configVersionId());
    }

    @Transactional
    public long beginAsr(RuntimePrincipal principal, String requestId, long relayVersionId, byte[] audio)
    {
        Integer existing = jdbc.queryForObject("select count(*) from s_operation where session_id=? and client_request_id=?",
            Integer.class, principal.sessionId(), requestId);
        if (existing != null && existing > 0) throw problem("REQUEST_ALREADY_USED");
        long operationId = id();
        jdbc.update("insert into s_operation (id,created_at,updated_at,account_id,session_id,client_request_id," +
            "operation_type,ordinal,config_resource_id,status,input_hash,started_at) " +
            "values (?,utc_timestamp(3),utc_timestamp(3),?,?,?,'ASR',0,?,'RUNNING',?,utc_timestamp(3))",
            operationId, principal.accountId(), principal.sessionId(), requestId, relayVersionId,
            sha256(audio));
        fact(principal.accountId(), principal.applicationId(), principal.sessionId(), null,
            operationId, "ASR", "STARTED", null, null, null, null);
        return operationId;
    }

    @Transactional
    public void endAsr(RuntimePrincipal principal, long operationId, String status, String code, String providerRequestId,
        Long durationMs)
    {
        if (!java.util.Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(status)) throw problem("OPERATION_STATE_INVALID");
        int changed = jdbc.update("update s_operation set status=?,error_code=?,provider_request_id=?," +
            "result_summary=json_object('durationMs',?)," +
            "finished_at=utc_timestamp(3),updated_at=utc_timestamp(3) where id=? and account_id=? and session_id=? " +
            "and operation_type='ASR' and status='RUNNING'", status, code, providerRequestId, durationMs,
            operationId, principal.accountId(), principal.sessionId());
        if (changed == 1) fact(principal.accountId(), principal.applicationId(), principal.sessionId(), null,
            operationId, "ASR", status, code, providerRequestId, null, null, durationMs);
    }

    @Transactional
    public void expireDebug(long sessionId)
    {
        Long turnId = jdbc.query("select active_turn_id from s_session where id=? for update",
            rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null, sessionId);
        if (turnId != null)
        {
            String type = jdbc.queryForObject("select turn_type from s_turn where id=?", String.class, turnId);
            if ("CHAT".equals(type)) interrupt(turnId, "REVOKED");
        }
    }

    @Transactional
    public void unknownInFlight(long sessionId, String reason)
    {
        java.util.List<InterruptedOp> pending = jdbc.query(
            "select o.id,o.operation_type,s.account_id,s.application_id,s.id from s_operation o " +
                "join s_session s on s.id=o.session_id where o.session_id=? and o.operation_type in ('LLM','TOOL') " +
                "and o.status='RUNNING' for update",
            (rs, index) -> new InterruptedOp(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5)), sessionId);
        jdbc.update("update s_operation set status='UNKNOWN',error_code=?,finished_at=utc_timestamp(3)," +
            "updated_at=utc_timestamp(3) where session_id=? and operation_type in ('LLM','TOOL') and status='RUNNING'",
            reason, sessionId);
        for (InterruptedOp operation : pending)
        {
            Long turnId = jdbc.queryForObject("select turn_id from s_operation where id=?", Long.class, operation.id());
            if (turnId != null)
                fact(operation.accountId(), operation.applicationId(), operation.sessionId(), turnId,
                    operation.id(), operation.type(), "UNKNOWN", reason, null, null, null);
        }
    }

    private void interrupt(long turnId, String reason)
    {
        jdbc.update("update s_turn set status='INTERRUPTED',text_status=case when turn_type='CHAT' " +
            "and text_status='RUNNING' then 'INTERRUPTED' else text_status end,cancel_reason=?," +
            "ended_at=utc_timestamp(3),updated_at=utc_timestamp(3) where id=? and status='RUNNING'", reason, turnId);
        jdbc.update("update s_session set active_turn_id=null,updated_at=utc_timestamp(3),revision=revision+1 " +
            "where active_turn_id=?", turnId);
        // The upstream may have accepted work; never label an in-flight call free or cancelled.
        java.util.List<InterruptedOp> pending = jdbc.query(
            "select o.id,o.operation_type,s.account_id,s.application_id,s.id from s_operation o " +
                "join s_session s on s.id=o.session_id where o.turn_id=? and o.status='RUNNING'",
            (rs, index) -> new InterruptedOp(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5)), turnId);
        jdbc.update("update s_operation set status='UNKNOWN',error_code='TURN_INTERRUPTED'," +
            "finished_at=utc_timestamp(3),updated_at=utc_timestamp(3) where turn_id=? and status='RUNNING'", turnId);
        for (InterruptedOp operation : pending)
            fact(operation.accountId(), operation.applicationId(), operation.sessionId(), turnId,
                operation.id(), operation.type(), "UNKNOWN", "TURN_INTERRUPTED", null, null, null);
    }

    private void fact(RuntimePrincipal principal, long turnId, long operationId, String type, String status,
        String code, String providerRequestId, Long inputTokens, Long outputTokens)
    {
        fact(principal.accountId(), principal.applicationId(), principal.sessionId(), turnId,
            operationId, type, status, code, providerRequestId, inputTokens, outputTokens);
    }

    private void fact(long accountId, long applicationId, long sessionId, Long turnId, long operationId,
        String type, String status, String code, String providerRequestId, Long inputTokens, Long outputTokens)
    { fact(accountId, applicationId, sessionId, turnId, operationId, type, status, code,
        providerRequestId, inputTokens, outputTokens, null); }

    private void fact(long accountId, long applicationId, long sessionId, Long turnId, long operationId,
        String type, String status, String code, String providerRequestId, Long inputTokens, Long outputTokens,
        Long audioDurationMs)
    {
        try
        {
            String eventId = operationId + ":" + status;
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("eventId", eventId);
            payload.put("operationKey", Long.toString(operationId));
            payload.put("accountId", accountId);
            payload.put("applicationId", applicationId);
            payload.put("sessionId", sessionId);
            payload.put("turnId", turnId);
            payload.put("capability", type);
            payload.put("status", status);
            payload.put("providerRequestId", providerRequestId);
            payload.put("errorCode", code);
            Map<String, Object> usage = new java.util.LinkedHashMap<>();
            usage.put("usageAvailable", inputTokens != null || outputTokens != null || audioDurationMs != null);
            usage.put("inputTokens", inputTokens);
            usage.put("outputTokens", outputTokens);
            usage.put("audioDurationMs", audioDurationMs);
            payload.put("usage", usage);
            payload.put("costSource", "UNKNOWN");
            jdbc.update("insert into s_outbox (id,created_at,updated_at,account_id,event_id,event_type,aggregate_type," +
                "aggregate_id,schema_version,trace_id,payload,status,attempt_count,next_run_at) " +
                "values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,1,?,cast(? as json),'PENDING',0,utc_timestamp(3))",
                accountId, eventId, "CALL_FACT_RECORDED", type + "_OPERATION", Long.toString(operationId),
                eventId, json.writeValueAsString(payload));
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
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static byte[] sha256(byte[] value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static RuntimeProblem problem(String code)
    { return new RuntimeProblem(HttpStatus.CONFLICT, code, code); }

    public record Started(long turnId, Long priorTurnId, String externalUserId) { }
    private record Session(long nextTurnNo, Long activeTurnId, String externalUserId) { }
    private record Existing(long id, byte[] hash) { }
    private record InterruptedOp(long id, String type, long accountId, long applicationId, long sessionId) { }
}
