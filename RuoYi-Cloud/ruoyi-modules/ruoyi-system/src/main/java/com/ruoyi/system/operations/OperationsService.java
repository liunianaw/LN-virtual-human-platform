package com.ruoyi.system.operations;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.dto.AvatarAttemptRequest;
import com.ruoyi.system.asset.service.IAvatarProductionService;
import com.ruoyi.system.operations.OperationsController.CallReview;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-owned calling facts, their monotonic transitions, and cross-account operator views. */
@Service
public class OperationsService
{
    private static final String CONSUMER = "call-record-event-ingress";
    private final JdbcTemplate jdbc;
    private final IAvatarProductionService production;

    public OperationsService(JdbcTemplate jdbc, IAvatarProductionService production)
    { this.jdbc = jdbc; this.production = production; }

    @Transactional
    public Map<String, Object> accept(CallFactEvent event)
    {
        validateEvent(event);
        if(event.operationKey().startsWith("tts-attempt:")) throw problem(HttpStatus.BAD_REQUEST,"Voice 尝试须使用版本化专用事实入口");
        lockUsageAccount(event.accountId());
        byte[] payloadHash = hash(canonical(event));
        Map<String, Object> prior = jdbc.query("select payload_hash from p_inbox where consumer_name=? and event_id=? for update",
            rs -> rs.next() ? Map.of("payloadHash", rs.getBytes(1)) : null, CONSUMER, event.eventId());
        if (prior != null)
        {
            if (!MessageDigest.isEqual((byte[]) prior.get("payloadHash"), payloadHash))
                throw problem(HttpStatus.CONFLICT, "同一调用事件内容冲突");
            return Map.of("accepted", false, "duplicate", true);
        }
        upsert(event);
        jdbc.update("insert into p_inbox (id,created_at,updated_at,account_id,consumer_name,event_id,payload_hash,processed_at) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?, ?,utc_timestamp(3))",
            event.accountId(), CONSUMER, event.eventId(), payloadHash);
        return Map.of("accepted", true, "duplicate", false);
    }

    /** Generation facts already enter system inside the Worker transaction, so do not create a second transport event. */
    public void generation(long attemptId, long accountId, String status, String providerRequestId, String errorCode)
    {
        lockUsageAccount(accountId);
        Long reservationId = jdbc.query("select p.id from p_generation_attempt a join p_generation_task t on t.id=a.task_id and t.account_id=a.account_id join p_point_reservation p on p.id=t.quota_reservation_id where a.id=? and a.account_id=?",
            rs -> rs.next() ? rs.getLong(1) : null, attemptId, accountId);
        CallFactEvent event = new CallFactEvent("generation-" + attemptId + "-" + UUID.randomUUID().toString().replace("-", ""),
            "generation:" + attemptId, accountId, "GENERATION", status, null, null, null, reservationId, providerRequestId,
            new CallFactEvent.Usage(null, null, null, false), null, null, "UNKNOWN", errorCode);
        validateEvent(event); upsert(event);
    }

    public Map<String, Object> tasks(String accountId, String status, String errorCode, String from, String to, Integer pageNum, Integer pageSize)
    {
        Query query = query(accountId, status, errorCode, from, to, pageNum, pageSize, true);
        String where = " where 1=1" + query.where();
        String unknown = "UNKNOWN".equals(status) ? " and exists (select 1 from p_generation_attempt a where a.task_id=t.id and a.account_id=t.account_id and a.status='UNKNOWN')" : "";
        Long total = jdbc.queryForObject("select count(*) from p_generation_task t" + where + unknown, Long.class, query.args().toArray());
        List<Map<String, Object>> rows = jdbc.query("select t.id,t.account_id,t.status,t.internal_state,t.progress,t.error_code,t.created_at,t.updated_at,"
            + "(select count(*) from p_generation_attempt a where a.task_id=t.id and a.account_id=t.account_id and a.status='UNKNOWN') unknown_attempts "
            + "from p_generation_task t" + where + unknown + " order by t.created_at desc,t.id desc limit ? offset ?",
            (rs, n) -> row(rs, "id", "accountId", "status", "internalState", "progress", "errorCode", "createdAt", "updatedAt", "unknownAttempts"),
            append(query.args(), query.size(), query.offset()).toArray());
        return page(rows.stream().map(this::taskSummary).toList(), total == null ? 0 : total, query);
    }

    public Map<String, Object> task(long taskId)
    {
        Map<String, Object> task = jdbc.query("select id,account_id,status,internal_state,progress,error_code,created_at,updated_at from p_generation_task where id=?",
            rs -> rs.next() ? row(rs, "id","accountId","status","internalState","progress","errorCode","createdAt","updatedAt") : null, taskId);
        if (task == null) throw problem(HttpStatus.NOT_FOUND, "制作任务不存在");
        List<Map<String, Object>> steps = jdbc.query("select s.id,s.step_key,s.step_type,s.action_code,s.status,s.error_code,s.created_at,s.updated_at from p_generation_step s where s.task_id=? order by s.created_at,s.id",
            (rs, n) -> row(rs, "stepId","stepKey","stepType","actionCode","status","errorCode","createdAt","updatedAt"), taskId);
        List<Map<String, Object>> attempts = jdbc.query("select a.id,a.step_id,a.status,a.provider_request_id,a.provider_task_id,a.model_id,a.error_code,a.reviewed_by,a.reviewed_at,a.review_note,a.created_at,a.updated_at,"
                + "case when a.provider_task_id is not null or json_extract(s.result_metadata,'$.providerReceipt.providerTaskId') is not null or json_extract(s.result_metadata,'$.providerReceipt.imageUrl') is not null then 1 else 0 end "
                + "from p_generation_attempt a join p_generation_step s on s.id=a.step_id where a.task_id=? order by a.created_at,a.id",
            (rs, n) -> attempt(row(rs, "attemptId","stepId","status","providerRequestId","providerTaskId","modelId","errorCode","reviewedBy","reviewedAt","reviewNote","createdAt","updatedAt","hasRecoveryEvidence")), taskId);
        return Map.of("task", taskSummary(task), "steps", steps, "attempts", attempts, "allowedOperations", attempts.stream().anyMatch(a -> ((List<?>) a.get("allowedOperations")).contains("reconcile")) ? List.of("reconcile") : List.of());
    }

    @Transactional
    public Map<String, Object> reconcile(long operatorId, long taskId, long attemptId, String reason, String ifMatch, String key)
    {
        requireReason(reason, 500); requireKey(key);
        String scope = "ops-reconcile:" + attemptId;
        byte[] requestHash = hash(reason.trim());
        Map<String, Object> existing = idempotency(operatorId, scope, key, requestHash);
        if (existing != null) return operationResult(existing.get("id"), existing.get("status"), attemptId);
        Map<String, Object> context = jdbc.query("select a.id,a.account_id,a.task_id,a.status,a.provider_task_id,a.provider_request_id,a.updated_at,"
                + "t.avatar_id,t.avatar_version_id,s.action_code,sel.revision action_revision,case when a.provider_task_id is not null or json_extract(s.result_metadata,'$.providerReceipt.providerTaskId') is not null or json_extract(s.result_metadata,'$.providerReceipt.imageUrl') is not null then 1 else 0 end has_recovery_evidence from p_generation_attempt a "
                + "join p_generation_task t on t.id=a.task_id and t.account_id=a.account_id join p_generation_step s on s.id=a.step_id "
                + "left join p_avatar_action_selection sel on sel.account_id=a.account_id and sel.avatar_version_id=t.avatar_version_id and sel.action_code=s.action_code "
                + "where a.id=? and a.task_id=? for update",
            rs -> rs.next() ? row(rs,"attemptId","accountId","taskId","status","providerTaskId","providerRequestId","updatedAt","avatarId","avatarVersionId","actionCode","actionRevision","hasRecoveryEvidence") : null, attemptId, taskId);
        if (context == null) throw problem(HttpStatus.NOT_FOUND, "尝试不属于指定任务");
        if (!etag(context).equals(normalizeEtag(ifMatch))) throw problem(HttpStatus.PRECONDITION_FAILED, "尝试状态已变化");
        if (!List.of("UNKNOWN", "FAILED").contains(context.get("status")) || !asBoolean(context.get("hasRecoveryEvidence")))
            throw problem(HttpStatus.CONFLICT, "没有可安全核对的原厂商任务证据");
        long operationId = nextId();
        jdbc.update("insert into p_api_idempotency (id,created_at,updated_at,account_id,scope,request_id,request_hash,resource_type,resource_id,status,expires_at) values (?,utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,'PROCESSING',date_add(utc_timestamp(3),interval 30 day))",
            operationId, operatorId, scope, key, requestHash, "GENERATION_ATTEMPT", attemptId);
        try
        {
            production.recover((Long) context.get("accountId"), (Long) context.get("avatarId"), (Long) context.get("avatarVersionId"),
                (String) context.get("actionCode"), attemptId, new AvatarAttemptRequest("admin-" + operationId, ((Number) context.get("actionRevision")).longValue()));
            jdbc.update("update p_generation_attempt set reviewed_by=?,reviewed_at=utc_timestamp(3),review_note=?,updated_at=utc_timestamp(3) where id=?", operatorId, reason.trim(), attemptId);
            jdbc.update("insert into p_generation_reconciliation_audit (id,attempt_id,task_id,actor_id,reason,status,created_at) values (uuid_short(),?,?,?,?, 'COMPLETED',utc_timestamp(3))", attemptId, taskId, operatorId, reason.trim());
            jdbc.update("update p_api_idempotency set status='SUCCEEDED',updated_at=utc_timestamp(3) where id=?", operationId);
            return operationResult(operationId, "COMPLETED", attemptId);
        }
        catch (RuntimeException error)
        {
            jdbc.update("update p_api_idempotency set status='FAILED',error_code='RECONCILIATION_REJECTED',updated_at=utc_timestamp(3) where id=?", operationId);
            throw error;
        }
    }

    public Map<String, Object> calls(String accountId, String capability, String status, String taskId, String sessionId,
        String from, String to, Integer pageNum, Integer pageSize)
    {
        Query query = query(accountId, status, null, from, to, pageNum, pageSize, false);
        if (capability != null && !capability.isBlank()) { require(capability, List.of("GENERATION","TTS","LLM","ASR","TOOL","CONTEXT")); query = query.add(" and c.capability=?", capability); }
        if (sessionId != null && !sessionId.isBlank()) query = query.add(" and c.session_id=?", positive(sessionId));
        if (taskId != null && !taskId.isBlank()) query = query.add(" and c.operation_key in (select concat('generation:',a.id) from p_generation_attempt a where a.task_id=?)", positive(taskId));
        String where = " where 1=1" + query.where();
        Long total = jdbc.queryForObject("select count(*) from p_call_record c" + where, Long.class, query.args().toArray());
        List<Map<String,Object>> rows = jdbc.query("select c.id,c.account_id,c.operation_key,c.capability,c.status,c.provider_code,c.model_id,c.provider_request_id,c.input_tokens,c.output_tokens,c.input_chars,c.image_count,c.audio_duration_ms,c.usage_available,c.cost_amount,c.currency,c.cost_source,c.error_code,c.created_at,c.updated_at,c.session_id,c.turn_id "
            + "from p_call_record c" + where + " order by c.created_at desc,c.id desc limit ? offset ?",
            (rs, n) -> row(rs,"id","accountId","operationKey","capability","status","providerCode","modelId","providerRequestId","inputTokens","outputTokens","inputChars","imageCount","audioDurationMs","usageAvailable","costAmount","currency","costSource","errorCode","createdAt","updatedAt","sessionId","turnId"),
            append(query.args(), query.size(), query.offset()).toArray());
        return page(rows.stream().map(this::callSummary).toList(), total == null ? 0 : total, query);
    }

    public Map<String, Object> call(long callId)
    {
        Map<String,Object> value = jdbc.query("select id,account_id,operation_key,capability,status,provider_code,model_id,provider_request_id,input_tokens,output_tokens,input_chars,image_count,audio_duration_ms,usage_available,cost_amount,currency,cost_source,error_code,created_at,updated_at,session_id,turn_id from p_call_record where id=?",
            rs -> rs.next() ? row(rs,"callId","accountId","operationKey","capability","status","providerCode","modelId","providerRequestId","inputTokens","outputTokens","inputChars","imageCount","audioDurationMs","usageAvailable","costAmount","currency","costSource","errorCode","createdAt","updatedAt","sessionId","turnId") : null, callId);
        if (value == null) throw problem(HttpStatus.NOT_FOUND, "调用记录不存在");
        List<Map<String,Object>> reviews = jdbc.query("select actor_id,reviewed_status,evidence_note,cost_amount,currency,cost_source,created_at from p_call_review where call_id=? order by created_at desc,id desc",
            (rs,n) -> row(rs,"actorId","reviewedStatus","evidenceNote","costAmount","currency","costSource","createdAt"), callId);
        Map<String,Object> result = new LinkedHashMap<>(callSummary(value)); result.put("reviews", reviews); return result;
    }

    @Transactional
    public Map<String,Object> review(long actorId, long callId, CallReview review, String ifMatch, String key)
    {
        Long usageAccount = jdbc.query("select account_id from p_call_record where id=?",
            rs -> rs.next() ? rs.getLong(1) : null, callId);
        if (usageAccount == null) throw problem(HttpStatus.NOT_FOUND, "调用记录不存在");
        lockUsageAccount(usageAccount);
        if (review == null) throw problem(HttpStatus.BAD_REQUEST, "缺少核对依据"); requireReason(review.evidenceNote(), 1000); requireKey(key);
        require(review.reviewedStatus(), List.of("SUCCEEDED","FAILED","CANCELLED"));
        String source = review.costSource() == null ? "CONSOLE" : review.costSource(); require(source, List.of("CONSOLE","ESTIMATED"));
        if (review.costAmount() != null && review.costAmount().signum() < 0) throw problem(HttpStatus.BAD_REQUEST, "成本不能为负数");
        if (review.currency() != null && !review.currency().matches("[A-Z]{3}")) throw problem(HttpStatus.BAD_REQUEST, "币种无效");
        String scope = "ops-call-review:" + callId;
        byte[] requestHash = hash(review.reviewedStatus() + "|" + review.evidenceNote().trim() + "|" + review.costAmount() + "|" + review.currency() + "|" + source);
        Map<String,Object> existing = idempotency(actorId, scope, key, requestHash);
        if (existing != null) return operationResult(existing.get("id"), existing.get("status"), callId);
        Map<String,Object> current = jdbc.query("select id,account_id,operation_key,capability,status,application_id,session_id,turn_id,provider_request_id,input_tokens,output_tokens,input_chars,image_count,audio_duration_ms,usage_available,cost_amount,currency,cost_source,error_code,created_at,updated_at,date(created_at) from p_call_record where id=? for update",
            rs -> rs.next() ? callRow(rs) : null, callId);
        if (current == null) throw problem(HttpStatus.NOT_FOUND, "调用记录不存在");
        if(String.valueOf(current.get("operationKey")).startsWith("tts-attempt:")) throw problem(HttpStatus.CONFLICT,"Voice 尝试通过原执行证据核对，不能修改逻辑用量");
        if (!etag(current).equals(normalizeEtag(ifMatch))) throw problem(HttpStatus.PRECONDITION_FAILED, "调用事实已变化");
        if (!"UNKNOWN".equals(current.get("status"))) throw problem(HttpStatus.CONFLICT, "只有未知调用可由人工核对终态");
        long operationId = nextId();
        jdbc.update("insert into p_api_idempotency (id,created_at,updated_at,account_id,scope,request_id,request_hash,resource_type,resource_id,status,expires_at) values (?,utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,'PROCESSING',date_add(utc_timestamp(3),interval 30 day))",
            operationId, actorId, scope, key, requestHash, "CALL_RECORD", callId);
        CallFactEvent event = new CallFactEvent("manual-" + operationId, (String) current.get("operationKey"), (Long) current.get("accountId"), (String) current.get("capability"), review.reviewedStatus(),
            (Long) current.get("applicationId"), (Long) current.get("sessionId"), (Long) current.get("turnId"), (String) current.get("providerRequestId"),
            new CallFactEvent.Usage((Long) current.get("inputChars"),(Long) current.get("imageCount"),(Long) current.get("audioDurationMs"),asBoolean(current.get("usageAvailable"))), review.costAmount(), review.currency(), source, (String) current.get("errorCode"));
        upsert(event);
        jdbc.update("insert into p_call_review (id,call_id,actor_id,reviewed_status,evidence_note,cost_amount,currency,cost_source,created_at) values (uuid_short(),?,?,?,?,?,?,?,utc_timestamp(3))",
            callId, actorId, review.reviewedStatus(), review.evidenceNote().trim(), review.costAmount(), review.currency(), source);
        jdbc.update("update p_api_idempotency set status='SUCCEEDED',updated_at=utc_timestamp(3) where id=?", operationId);
        return operationResult(operationId, "COMPLETED", callId);
    }

    public Map<String,Object> operation(long operationId)
    {
        Map<String,Object> result = jdbc.query("select id,status,resource_id,error_code from p_api_idempotency where id=? and resource_type in ('GENERATION_ATTEMPT','CALL_RECORD')",
            rs -> rs.next() ? Map.of("operationId", Long.toString(rs.getLong(1)), "status", "SUCCEEDED".equals(rs.getString(2)) ? "COMPLETED" : rs.getString(2), "resourceId", Long.toString(rs.getLong(3)), "safeMessage", rs.getString(4) == null ? "" : rs.getString(4)) : null, operationId);
        if (result == null) throw problem(HttpStatus.NOT_FOUND, "操作不存在"); return result;
    }

    private void upsert(CallFactEvent event)
    {
        Map<String,Object> old = jdbc.query("select id,account_id,operation_key,capability,status,application_id,session_id,turn_id,provider_request_id,input_tokens,output_tokens,input_chars,image_count,audio_duration_ms,usage_available,cost_amount,currency,cost_source,error_code,created_at,updated_at,date(created_at) from p_call_record where operation_key=? for update",
            rs -> rs.next() ? callRow(rs) : null, event.operationKey());
        if (old == null)
        {
            long id = nextId();
            jdbc.update("insert into p_call_record (id,created_at,updated_at,account_id,application_id,session_id,turn_id,quota_reservation_id,operation_key,capability,billing_owner,provider_request_id,status,input_tokens,output_tokens,input_chars,image_count,audio_duration_ms,usage_available,cost_amount,currency,cost_source,error_code,finished_at,expires_at) values (?,utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,case when ? in ('SUCCEEDED','FAILED','UNKNOWN','CANCELLED') then utc_timestamp(3) else null end,null)",
                id, event.accountId(), event.applicationId(), event.sessionId(), event.turnId(), event.quotaReservationId(), event.operationKey(),
                event.capability(), billingOwner(event.capability()), event.providerRequestId(), event.status(),
                event.usage().inputTokens(), event.usage().outputTokens(), event.usage().inputChars(),
                event.usage().imageCount(), event.usage().audioDurationMs(), bool(event.usage().usageAvailable()),
                event.costAmount(), event.currency(), event.costSource(), event.errorCode(), event.status());
            java.sql.Date createdDate = jdbc.queryForObject("select date(created_at) from p_call_record where id=?", java.sql.Date.class, id);
            if (createdDate == null) throw new IllegalStateException("Call date is unavailable");
            daily(CallRow.of(id, event, createdDate.toLocalDate()), 1, true);
            return;
        }
        CallRow before = CallRow.from(old);
        // A terminal fact may reach system before its STARTED outbox event on another publisher node.
        if ("STARTED".equals(event.status()) && !"STARTED".equals(before.status)) return;
        assertTransition(before.status, event.status());
        CallRow after = before.merge(event);
        if (before.sameFacts(after)) return;
        boolean currencyChanged = !java.util.Objects.equals(
            before.currency == null ? "UNKNOWN" : before.currency,
            after.currency == null ? "UNKNOWN" : after.currency);
        daily(before, -1, currencyChanged);
        jdbc.update("update p_call_record set status=?,provider_request_id=coalesce(?,provider_request_id),quota_reservation_id=coalesce(?,quota_reservation_id),input_tokens=coalesce(?,input_tokens),output_tokens=coalesce(?,output_tokens),input_chars=coalesce(?,input_chars),image_count=coalesce(?,image_count),audio_duration_ms=coalesce(?,audio_duration_ms),usage_available=?,cost_amount=coalesce(?,cost_amount),currency=coalesce(?,currency),cost_source=?,error_code=coalesce(?,error_code),finished_at=case when ? in ('SUCCEEDED','FAILED','UNKNOWN','CANCELLED') then coalesce(finished_at,utc_timestamp(3)) else finished_at end,expires_at=null,updated_at=utc_timestamp(3) where id=?",
            after.status, event.providerRequestId(), event.quotaReservationId(), event.usage().inputTokens(), event.usage().outputTokens(),
            event.usage().inputChars(), event.usage().imageCount(), event.usage().audioDurationMs(),
            bool(event.usage().usageAvailable()), event.costAmount(), event.currency(), event.costSource(),
            event.errorCode(), after.status, before.id);
        daily(after, 1, currencyChanged);
    }

    private void lockUsageAccount(long accountId)
    {
        Long id = jdbc.query("select user_id from sys_user where user_id=? for update",
            rs -> rs.next() ? rs.getLong(1) : null, accountId);
        if (id == null) throw problem(HttpStatus.NOT_FOUND, "调用归属账号不存在");
    }

    private void daily(CallRow value, int sign, boolean request)
    {
        String currency = value.currency == null ? "UNKNOWN" : value.currency;
        long success = "SUCCEEDED".equals(value.status) ? sign : 0;
        long failure = "FAILED".equals(value.status) ? sign : 0;
        long unknown = "UNKNOWN".equals(value.status) ? sign : 0;
        long cancelled = "CANCELLED".equals(value.status) ? sign : 0;
        long known = value.usageAvailable ? sign : 0, knownCost = value.costAmount == null ? 0 : sign;
        if (sign < 0)
        {
            // MySQL checks the candidate INSERT row before ON DUPLICATE KEY UPDATE.
            // Negative usage deltas must update the existing bucket directly.
            int changed = jdbc.update("update p_usage_daily set request_count=request_count+?," +
                "success_count=success_count+?,failure_count=failure_count+?,unknown_count=unknown_count+?," +
                "input_tokens=input_tokens+?,output_tokens=output_tokens+?,input_chars=input_chars+?," +
                "image_count=image_count+?,audio_duration_ms=audio_duration_ms+?," +
                "known_usage_count=known_usage_count+?,known_cost_count=known_cost_count+?," +
                "cost_amount=cost_amount+?,cancelled_count=cancelled_count+?,updated_at=utc_timestamp(3) " +
                "where account_id=? and application_scope_id=? and usage_date=? and capability=? " +
                "and billing_owner=? and currency=?",
                request ? sign : 0L, success, failure, unknown,
                nullable(value.inputTokens, sign), nullable(value.outputTokens, sign),
                nullable(value.inputChars, sign), nullable(value.imageCount, sign),
                nullable(value.audioDurationMs, sign), known, knownCost,
                value.costAmount == null ? BigDecimal.ZERO : value.costAmount.multiply(BigDecimal.valueOf(sign)),
                cancelled, value.accountId, value.applicationId == null ? 0L : value.applicationId,
                java.sql.Date.valueOf(value.usageDate), value.capability, billingOwner(value.capability), currency);
            if (changed != 1) throw new IllegalStateException("Existing usage bucket is unavailable");
            return;
        }
        jdbc.update("insert into p_usage_daily (id,created_at,updated_at,account_id,application_scope_id,usage_date,capability,billing_owner,request_count,success_count,failure_count,unknown_count,input_tokens,output_tokens,input_chars,image_count,audio_duration_ms,known_usage_count,known_cost_count,cost_amount,currency,expires_at,cancelled_count) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,null,?) on duplicate key update request_count=request_count+values(request_count),success_count=success_count+values(success_count),failure_count=failure_count+values(failure_count),unknown_count=unknown_count+values(unknown_count),input_tokens=input_tokens+values(input_tokens),output_tokens=output_tokens+values(output_tokens),input_chars=input_chars+values(input_chars),image_count=image_count+values(image_count),audio_duration_ms=audio_duration_ms+values(audio_duration_ms),known_usage_count=known_usage_count+values(known_usage_count),known_cost_count=known_cost_count+values(known_cost_count),cost_amount=cost_amount+values(cost_amount),cancelled_count=cancelled_count+values(cancelled_count),updated_at=utc_timestamp(3)",
            value.accountId, value.applicationId == null ? 0L : value.applicationId, java.sql.Date.valueOf(value.usageDate), value.capability,
            billingOwner(value.capability), request ? sign : 0L, success, failure, unknown,
            nullable(value.inputTokens, sign), nullable(value.outputTokens, sign),
            nullable(value.inputChars, sign), nullable(value.imageCount, sign), nullable(value.audioDurationMs, sign),
            known, knownCost, value.costAmount == null ? BigDecimal.ZERO : value.costAmount.multiply(BigDecimal.valueOf(sign)),
            currency, cancelled);
    }

    private static String billingOwner(String capability)
    { return List.of("LLM", "TOOL", "CONTEXT").contains(capability) ? "DEVELOPER" : "PLATFORM"; }

    private Map<String,Object> taskSummary(Map<String,Object> row) { Map<String,Object> result = new LinkedHashMap<>(row); result.put("taskId", Long.toString((Long) result.remove("id"))); result.put("accountId", Long.toString((Long) result.get("accountId"))); result.remove("updatedAt"); return result; }
    private Map<String,Object> attempt(Map<String,Object> row) { Map<String,Object> result = new LinkedHashMap<>(row); result.put("attemptId", Long.toString((Long) result.get("attemptId"))); result.put("stepId", Long.toString((Long) result.get("stepId"))); result.put("etag", etag(result)); boolean canReconcile=("UNKNOWN".equals(result.get("status")) || "FAILED".equals(result.get("status"))) && asBoolean(result.remove("hasRecoveryEvidence")); result.put("allowedOperations", canReconcile ? List.of("reconcile") : List.of()); return result; }
    private Map<String,Object> callSummary(Map<String,Object> row) { Map<String,Object> result = new LinkedHashMap<>(row); Object id=result.remove("id"); if (id != null) result.put("callId", Long.toString((Long) id)); else if (result.get("callId") instanceof Long callId) result.put("callId", Long.toString(callId)); result.put("accountId", Long.toString((Long) result.get("accountId"))); result.put("etag", etag(result)); result.put("factKind",String.valueOf(result.get("operationKey")).startsWith("tts-attempt:")?"ATTEMPT":"LOGICAL"); return result; }
    private static Map<String,Object> row(java.sql.ResultSet rs, String... names) throws java.sql.SQLException { Map<String,Object> result=new LinkedHashMap<>(); for (int i=0;i<names.length;i++) { Object value=rs.getObject(i+1); if (value instanceof java.sql.Timestamp stamp) value=stamp.toInstant().toString(); result.put(names[i],value); } return result; }
    private static Map<String,Object> callRow(java.sql.ResultSet rs) throws java.sql.SQLException { return row(rs,"id","accountId","operationKey","capability","status","applicationId","sessionId","turnId","providerRequestId","inputTokens","outputTokens","inputChars","imageCount","audioDurationMs","usageAvailable","costAmount","currency","costSource","errorCode","createdAt","updatedAt","usageDate"); }
    private static String etag(Map<String,Object> row) { return java.util.HexFormat.of().formatHex(hash(row.get("status")+"|"+row.get("updatedAt")+"|"+row.getOrDefault("providerTaskId",row.get("providerRequestId"))+"|"+row.get("costAmount"))); }
    private static String normalizeEtag(String value) { if (value == null || value.isBlank()) throw problem(HttpStatus.PRECONDITION_REQUIRED,"缺少 If-Match"); return value.replace("\"", ""); }
    private Map<String,Object> idempotency(long account,String scope,String key,byte[] requestHash) { return jdbc.query("select id,status,request_hash from p_api_idempotency where account_id=? and scope=? and request_id=? for update",rs->{if(!rs.next())return null;if(!MessageDigest.isEqual(requestHash,rs.getBytes(3)))throw problem(HttpStatus.CONFLICT,"幂等键参数冲突");return Map.of("id",rs.getLong(1),"status",rs.getString(2));},account,scope,key); }
    private static Map<String,Object> operationResult(Object id,Object status,long resource) { return Map.of("operationId",Long.toString(((Number)id).longValue()),"status","SUCCEEDED".equals(status)?"COMPLETED":status,"resourceId",Long.toString(resource)); }
    private static long nullable(Long value,int sign) { return value == null ? 0 : value * sign; }
    private static int bool(Boolean value) { return Boolean.TRUE.equals(value) ? 1 : 0; }
    private static boolean asBoolean(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0; }
    private static List<Object> append(List<Object> args,Object... tail) { List<Object> result=new ArrayList<>(args); java.util.Collections.addAll(result,tail); return result; }
    private static void assertTransition(String old,String next) { if (old.equals(next) || "STARTED".equals(old) || ("UNKNOWN".equals(old) && List.of("SUCCEEDED","FAILED","CANCELLED").contains(next))) return; throw problem(HttpStatus.CONFLICT,"调用终态需要人工核对，不能回退已确认事实"); }
    private static void validateEvent(CallFactEvent e) { if(e==null||!token(e.eventId(),64)||!token(e.operationKey(),128)||e.accountId()==null||e.accountId()<=0)throw problem(HttpStatus.BAD_REQUEST,"调用事件标识无效"); require(e.capability(),List.of("GENERATION","TTS","ASR","TOOL")); require(e.status(),List.of("STARTED","SUCCEEDED","FAILED","UNKNOWN","CANCELLED")); if(e.quotaReservationId()!=null&&e.quotaReservationId()<=0)throw problem(HttpStatus.BAD_REQUEST,"额度预占标识无效"); if(e.usage()==null)throw problem(HttpStatus.BAD_REQUEST,"调用用量字段缺失"); if(e.usage().inputTokens()!=null&&e.usage().inputTokens()<0||e.usage().outputTokens()!=null&&e.usage().outputTokens()<0||e.usage().inputChars()!=null&&e.usage().inputChars()<0||e.usage().imageCount()!=null&&e.usage().imageCount()<0||e.usage().audioDurationMs()!=null&&e.usage().audioDurationMs()<0||e.costAmount()!=null&&e.costAmount().signum()<0)throw problem(HttpStatus.BAD_REQUEST,"调用用量不能为负数"); require(e.costSource(),List.of("UNKNOWN","PROVIDER","CONSOLE","ESTIMATED")); if(e.currency()!=null&&!e.currency().matches("[A-Z]{3}"))throw problem(HttpStatus.BAD_REQUEST,"币种无效"); }
    private static boolean token(String value,int max){return value!=null&&value.matches("[A-Za-z0-9:._-]{1,"+max+"}");}
    private static void requireReason(String value,int max){if(value==null||value.isBlank()||value.trim().length()>max)throw problem(HttpStatus.BAD_REQUEST,"核对依据长度无效");}
    private static void requireKey(String value){if(value==null||!value.matches("[\\x21-\\x7e]{1,64}"))throw problem(HttpStatus.BAD_REQUEST,"Idempotency-Key 无效");}
    private static void require(String value,List<String> allowed){if(!allowed.contains(value))throw problem(HttpStatus.BAD_REQUEST,"调用状态或能力无效");}
    private static long positive(String value){try{long result=Long.parseLong(value);if(result<=0)throw new NumberFormatException();return result;}catch(NumberFormatException e){throw problem(HttpStatus.BAD_REQUEST,"筛选标识无效");}}
    private static byte[] hash(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String canonical(CallFactEvent event){return event.toString();}
    private long nextId(){Long id=jdbc.queryForObject("select uuid_short()",Long.class);if(id==null||id<=0)throw new IllegalStateException("无法生成业务标识");return id;}
    private static ServiceException problem(HttpStatus status,String message){return new ServiceException(message,status.value());}

    private Query query(String accountId,String status,String errorCode,String from,String to,Integer pageNum,Integer pageSize,boolean task)
    { Query q=new Query(Math.max(1,pageNum==null?1:pageNum),Math.min(100,Math.max(1,pageSize==null?20:pageSize))); String alias=task?"t":"c"; if(accountId!=null&&!accountId.isBlank())q=q.add(" and "+alias+".account_id=?",positive(accountId)); if(status!=null&&!status.isBlank()&&!"UNKNOWN".equals(status))q=q.add(" and "+alias+".status=?",status); if(errorCode!=null&&!errorCode.isBlank()&&task)q=q.add(" and "+alias+".error_code=?",errorCode); Instant start=parseTime(from),end=parseTime(to); if(start==null&&end==null&&!("UNKNOWN".equals(status)&&task))start=Instant.now().minus(7,ChronoUnit.DAYS); if(start!=null&&end!=null&&start.plus(30,ChronoUnit.DAYS).isBefore(end))throw problem(HttpStatus.BAD_REQUEST,"查询时间范围不能超过30天"); if(start!=null)q=q.add(" and "+alias+".created_at>=?",java.sql.Timestamp.from(start)); if(end!=null)q=q.add(" and "+alias+".created_at<=?",java.sql.Timestamp.from(end)); return q; }
    private static Instant parseTime(String value){if(value==null||value.isBlank())return null;try{return Instant.parse(value);}catch(Exception e){throw problem(HttpStatus.BAD_REQUEST,"时间必须为UTC RFC3339");}}
    private static Map<String,Object> page(List<Map<String,Object>> items,long total,Query q){return Map.of("items",items,"total",total,"pageNum",q.page(),"pageSize",q.size());}
    private record Query(String where,List<Object> args,int page,int size){Query(int page,int size){this("",List.of(),page,size);}Query add(String clause,Object arg){List<Object> next=new ArrayList<>(args);next.add(arg);return new Query(where+clause,next,page,size);}int offset(){return (page-1)*size;}}
    private record CallRow(long id, long accountId, String operationKey, String capability, String status,
        Long applicationId, Long sessionId, Long turnId, String providerRequestId, Long inputTokens,
        Long outputTokens, Long inputChars, Long imageCount, Long audioDurationMs, boolean usageAvailable,
        BigDecimal costAmount, String currency, String costSource, String errorCode, LocalDate usageDate)
    {
        static CallRow of(long id, CallFactEvent e, LocalDate usageDate)
        {
            return new CallRow(id, e.accountId(), e.operationKey(), e.capability(), e.status(), e.applicationId(),
                e.sessionId(), e.turnId(), e.providerRequestId(), e.usage().inputTokens(), e.usage().outputTokens(),
                e.usage().inputChars(), e.usage().imageCount(), e.usage().audioDurationMs(),
                Boolean.TRUE.equals(e.usage().usageAvailable()), e.costAmount(), e.currency(), e.costSource(), e.errorCode(), usageDate);
        }
        static CallRow from(Map<String,Object> m)
        {
            return new CallRow((Long)m.get("id"), (Long)m.get("accountId"), (String)m.get("operationKey"),
                (String)m.get("capability"), (String)m.get("status"), (Long)m.get("applicationId"),
                (Long)m.get("sessionId"), (Long)m.get("turnId"), (String)m.get("providerRequestId"),
                (Long)m.get("inputTokens"), (Long)m.get("outputTokens"), (Long)m.get("inputChars"),
                (Long)m.get("imageCount"), (Long)m.get("audioDurationMs"), asBoolean(m.get("usageAvailable")),
                (BigDecimal)m.get("costAmount"), (String)m.get("currency"), (String)m.get("costSource"),
                (String)m.get("errorCode"), ((java.sql.Date)m.get("usageDate")).toLocalDate());
        }
        CallRow merge(CallFactEvent e)
        {
            return new CallRow(id, accountId, operationKey, capability, e.status(), applicationId, sessionId, turnId,
                e.providerRequestId() == null ? providerRequestId : e.providerRequestId(),
                e.usage().inputTokens() == null ? inputTokens : e.usage().inputTokens(),
                e.usage().outputTokens() == null ? outputTokens : e.usage().outputTokens(),
                e.usage().inputChars() == null ? inputChars : e.usage().inputChars(),
                e.usage().imageCount() == null ? imageCount : e.usage().imageCount(),
                e.usage().audioDurationMs() == null ? audioDurationMs : e.usage().audioDurationMs(),
                usageAvailable || Boolean.TRUE.equals(e.usage().usageAvailable()),
                e.costAmount() == null ? costAmount : e.costAmount(),
                e.currency() == null ? currency : e.currency(),
                e.costSource() == null ? costSource : e.costSource(),
                e.errorCode() == null ? errorCode : e.errorCode(), usageDate);
        }
        boolean sameFacts(CallRow other) { return this.equals(other); }
    }

}
