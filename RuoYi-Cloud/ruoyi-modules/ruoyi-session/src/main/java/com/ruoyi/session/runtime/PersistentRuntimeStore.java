package com.ruoyi.session.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence over the existing session schema; no secret/token body is stored. */
@Repository
public class PersistentRuntimeStore
{
    private final JdbcTemplate jdbcTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public PersistentRuntimeStore(JdbcTemplate jdbcTemplate, com.fasterxml.jackson.databind.ObjectMapper json)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
    }

    @Transactional
    public long createSpeakTurn(RuntimePrincipal principal, String requestId, List<SegmentPlan> segments, long connectionEpoch)
    {
        try
        {
            Long nextTurnNo = jdbcTemplate.queryForObject("select next_turn_no from s_session where id = ? and account_id = ? and application_id = ? and status = 'ACTIVE' and expires_at > utc_timestamp(3) and connection_epoch = ? for update",
                    Long.class, principal.sessionId(), principal.accountId(), principal.applicationId(), connectionEpoch);
            if (nextTurnNo == null)
            {
                throw unavailable();
            }
            long turnId = nextId();
            Instant now = Instant.now();
            jdbcTemplate.update("update s_session set next_turn_no = ?, active_turn_id = ?, last_activity_at = ?, updated_at = ?, revision = revision + 1 where id = ?",
                    nextTurnNo + 1, turnId, now, now, principal.sessionId());
            jdbcTemplate.update("insert into s_turn (id,created_at,updated_at,account_id,session_id,turn_no,client_request_id,request_hash,turn_type,status,text_status,audio_status,playback_status,connection_epoch,input_source,include_in_history,last_event_seq,started_at) values (?,?,?,?,?,?,?,?, 'SPEAK','RUNNING','NOT_REQUESTED','RUNNING','WAITING',?,'TEXT',0,0,?)",
                    turnId, now, now, principal.accountId(), principal.sessionId(), nextTurnNo, requestId, hash(segments.stream().map(SegmentPlan::text).reduce("", String::concat)), connectionEpoch, now);
            for (SegmentPlan segment : segments)
            {
                long operationId = nextId();
                String operationRequestId = turnId + ":tts:" + segment.ordinal();
                jdbcTemplate.update("insert into s_operation (id,created_at,updated_at,account_id,session_id,turn_id,client_request_id,operation_type,ordinal,config_resource_id,status,playback_status,input_char_count,input_hash,result_summary) values (?,?,?,?,?,?,?,?,?,?,'QUEUED','WAITING',?,?,json_object('segmentId',?))",
                        operationId, now, now, principal.accountId(), principal.sessionId(), turnId, operationRequestId, "TTS", segment.ordinal(),
                        principal.voice().voiceVersionId(), (long) segment.text().codePointCount(0, segment.text().length()), hash(segment.text()), segment.segmentId());
            }
            return turnId;
        }
        catch (EmptyResultDataAccessException exception)
        {
            throw unavailable();
        }
    }

    @Transactional
    public void beginTts(RuntimePrincipal principal, TtsSynthesisWork work, long epoch, Long reservationId)
    {
        Operation operation = jdbcTemplate.query("select o.id,o.input_char_count,json_unquote(json_extract(o.result_summary,'$.segmentId')) " +
            "from s_operation o join s_session s on s.id=o.session_id and s.active_turn_id=o.turn_id " +
            "where o.turn_id=? and o.ordinal=? and o.operation_type='TTS' and o.status='QUEUED' " +
            "and s.account_id=? and s.application_id=? and s.session_snapshot_id=? and s.connection_epoch=? for update",
            rs -> rs.next() ? new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null,
            Long.parseLong(work.turnId()), work.ordinal(), principal.accountId(), principal.applicationId(),
            principal.snapshotId(), epoch);
        if (operation == null || !work.segmentId().equals(operation.segmentId())) throw unavailable();
        jdbcTemplate.update("update s_operation set status='RUNNING',quota_reservation_id=?,started_at=utc_timestamp(3)," +
            "updated_at=utc_timestamp(3) where id=? and status='QUEUED'", reservationId, operation.id());
        callOutbox(principal.accountId(), Long.parseLong(work.turnId()), work.segmentId(), operation.id(),
            "STARTED", operation.inputChars(), null, null, null);
    }

    @Transactional
    public void cancelNotSubmitted(RuntimePrincipal principal, TtsSynthesisWork work)
    {
        String prior = jdbcTemplate.query("select status from s_operation where turn_id=? and ordinal=? " +
            "and operation_type='TTS' and account_id=? and session_id=? for update",
            rs -> rs.next() ? rs.getString(1) : null, Long.parseLong(work.turnId()), work.ordinal(),
            principal.accountId(), principal.sessionId());
        if (prior == null || !java.util.Set.of("QUEUED", "RUNNING", "UNKNOWN").contains(prior)) return;
        jdbcTemplate.update("update s_operation set status='CANCELLED',error_code='TTS_NOT_SUBMITTED'," +
            "finished_at=utc_timestamp(3),updated_at=utc_timestamp(3) where turn_id=? and ordinal=? " +
            "and operation_type='TTS' and account_id=? and session_id=?", Long.parseLong(work.turnId()),
            work.ordinal(), principal.accountId(), principal.sessionId());
        if (!"QUEUED".equals(prior))
        {
            Operation operation = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) " +
                "from s_operation where turn_id=? and ordinal=? and operation_type='TTS'",
                rs -> rs.next() ? new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null,
                Long.parseLong(work.turnId()), work.ordinal());
            if (operation != null) callOutbox(principal.accountId(), Long.parseLong(work.turnId()), work.segmentId(),
                operation.id(), "CANCELLED", operation.inputChars(), null, "TTS_NOT_SUBMITTED", null);
        }
    }

    @Transactional
    public boolean markAudioReady(long turnId, int ordinal, RuntimePrincipal principal, TemporaryAudioReference audio, long bytes, long durationMs)
    {
        Instant now = Instant.now();
        Operation operation = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) from s_operation where turn_id = ? and ordinal = ? and operation_type = 'TTS'",
            rs -> rs.next() ? new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null, turnId, ordinal);
        if (operation == null)
        {
            return false;
        }
        int changed = jdbcTemplate.update("update s_operation set status = 'SUCCEEDED', result_summary = json_set(coalesce(result_summary,json_object()),'$.mediaId', ?), updated_at = ?, finished_at = ? where id = ? and status = 'RUNNING'",
                audio.mediaId(), now, now, operation.id());
        if (changed != 1) return false;
        jdbcTemplate.update("insert into s_temp_object (id,media_id,created_at,updated_at,account_id,session_id,turn_id,operation_id,purpose,storage_provider,bucket,object_key,size_bytes,status,expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?)",
                nextId(), audio.mediaId(), now, now, principal.accountId(), principal.sessionId(), turnId, operation.id(), "TTS_AUDIO", audio.storageProvider(),
                audio.bucket(), audio.objectKey(), bytes, audio.expiresAt());
        callOutbox(principal.accountId(), turnId, operation.segmentId(), operation.id(), "SUCCEEDED", operation.inputChars(), durationMs, null, null);
        return true;
    }

    /** A stopped turn must never deliver late audio, but a completed provider synthesis remains billable. */
    @Transactional
    public void markLateAudioSucceeded(RuntimePrincipal principal, TtsSynthesisWork work, long durationMs)
    {
        long turnId = Long.parseLong(work.turnId());
        Operation operation = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) " +
            "from s_operation where turn_id=? and ordinal=? and operation_type='TTS' and account_id=? " +
            "and session_id=? and status='UNKNOWN' and playback_status='STOPPED' for update",
            rs -> rs.next() ? new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null,
            turnId, work.ordinal(), principal.accountId(), principal.sessionId());
        if (operation == null || !work.segmentId().equals(operation.segmentId())) return;
        if (jdbcTemplate.update("update s_operation set status='SUCCEEDED',error_code=null,updated_at=utc_timestamp(3) " +
            "where id=? and status='UNKNOWN' and playback_status='STOPPED'", operation.id()) == 1)
            callOutbox(principal.accountId(), turnId, operation.segmentId(), operation.id(), "SUCCEEDED",
                operation.inputChars(), durationMs, null, null);
    }

    @Transactional
    public void markAudioFailed(long turnId, int ordinal, String failureCode)
    {
        Instant now = Instant.now();
        String prior = jdbcTemplate.query("select status from s_operation where turn_id=? and ordinal=? and operation_type='TTS' for update",
            rs -> rs.next() ? rs.getString(1) : null, turnId, ordinal);
        if (!"QUEUED".equals(prior) && !"RUNNING".equals(prior)) return;
        String terminal = "TTS_NOT_SUBMITTED".equals(failureCode) ? "CANCELLED" : "FAILED";
        int changed = jdbcTemplate.update("update s_operation set status = ?, result_summary = json_set(coalesce(result_summary,json_object()),'$.failureCode', ?), updated_at = ?, finished_at = ? where turn_id = ? and ordinal = ? and operation_type = 'TTS' and status = 'RUNNING'",
                terminal, failureCode, now, now, turnId, ordinal);
        if ("QUEUED".equals(prior)) changed = jdbcTemplate.update("update s_operation set status='FAILED',error_code=?,updated_at=?,finished_at=? where turn_id=? and ordinal=? and operation_type='TTS' and status='QUEUED'",
            failureCode, now, now, turnId, ordinal);
        if (changed != 1) return;
        Operation operation = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) from s_operation where turn_id=? and ordinal=? and operation_type='TTS'",
            rs -> rs.next() ? new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null, turnId, ordinal);
        if (operation != null && "RUNNING".equals(prior)) callOutbox(accountForTurn(turnId), turnId, operation.segmentId(), operation.id(), terminal, operation.inputChars(), null, failureCode, null);
    }

    public void playback(long turnId, int ordinal, PlaybackState state)
    {
        String playback = state.name();
        jdbcTemplate.update("update s_operation set playback_status = ?, updated_at = ?, finished_at = case when ? in ('ENDED','FAILED','SKIPPED') then ? else finished_at end where turn_id = ? and ordinal = ? and operation_type = 'TTS' and playback_status not in ('ENDED','FAILED','SKIPPED','STOPPED')",
                playback, Instant.now(), playback, Instant.now(), turnId, ordinal);
    }

    @Transactional
    public void stop(long turnId, String reason)
    {
        Instant now = Instant.now();
        List<Operation> running = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) " +
            "from s_operation where turn_id=? and operation_type='TTS' and status='RUNNING' for update",
            (rs, row) -> new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)), turnId);
        jdbcTemplate.update("update s_operation set status='CANCELLED',finished_at=?,updated_at=? where turn_id=? " +
            "and operation_type='TTS' and status='QUEUED'", now, now, turnId);
        jdbcTemplate.update("update s_operation set status='UNKNOWN',error_code='TURN_INTERRUPTED'," +
            "finished_at=?,updated_at=? where turn_id=? and operation_type='TTS' and status='RUNNING'", now, now, turnId);
        for (Operation operation : running)
            callOutbox(accountForTurn(turnId), turnId, operation.segmentId(), operation.id(), "UNKNOWN",
                operation.inputChars(), null, "TURN_INTERRUPTED", null);
        jdbcTemplate.update("update s_operation set playback_status = 'STOPPED', updated_at = ?, finished_at = ? where turn_id = ? and operation_type = 'TTS' and playback_status not in ('ENDED','FAILED','SKIPPED','STOPPED')",
                now, now, turnId);
        jdbcTemplate.update("update s_turn set status = 'INTERRUPTED', " +
            "text_status = 'NOT_REQUESTED', " +
            "audio_status = case when audio_status = 'COMPLETED' then 'COMPLETED' else 'INTERRUPTED' end, " +
            "playback_status = case when playback_status in ('COMPLETED','FAILED') then playback_status else 'STOPPED' end, " +
            "cancel_reason = ?, ended_at = coalesce(ended_at, ?), updated_at = ? where id = ? and status = 'RUNNING'",
                reason, now, now, turnId);
        jdbcTemplate.update("update s_session set active_turn_id=null,updated_at=?,revision=revision+1 where active_turn_id=?", now, turnId);
    }

    public void complete(long turnId)
    {
        Instant now = Instant.now();
        jdbcTemplate.update("update s_turn set status = 'COMPLETED', audio_status = 'COMPLETED', playback_status = 'COMPLETED', ended_at = ?, updated_at = ? where id = ? and status = 'RUNNING'", now, now, turnId);
        jdbcTemplate.update("update s_session set active_turn_id=null,updated_at=?,revision=revision+1 where active_turn_id=?", now, turnId);
    }

    @Transactional
    public void fail(long turnId)
    {
        Instant now = Instant.now();
        List<Operation> running = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) " +
            "from s_operation where turn_id=? and operation_type='TTS' and status='RUNNING' for update",
            (rs, row) -> new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)), turnId);
        jdbcTemplate.update("update s_operation set status='CANCELLED',finished_at=?,updated_at=? where turn_id=? " +
            "and operation_type='TTS' and status='QUEUED'", now, now, turnId);
        jdbcTemplate.update("update s_operation set status='UNKNOWN',error_code='TURN_FAILED',finished_at=?,updated_at=? " +
            "where turn_id=? and operation_type='TTS' and status='RUNNING'", now, now, turnId);
        for (Operation operation : running)
            callOutbox(accountForTurn(turnId), turnId, operation.segmentId(), operation.id(), "UNKNOWN",
                operation.inputChars(), null, "TURN_FAILED", null);
        jdbcTemplate.update("update s_turn set status = 'FAILED', audio_status = 'FAILED', playback_status = 'FAILED', ended_at = ?, updated_at = ? where id = ? and status = 'RUNNING'",
                now, now, turnId);
        jdbcTemplate.update("update s_session set active_turn_id=null,updated_at=?,revision=revision+1 where active_turn_id=?", now, turnId);
    }

    public void scheduleCleanup(TemporaryAudioReference audio)
    {
        jdbcTemplate.update("update s_temp_object set status = 'DELETE_PENDING', next_delete_at = coalesce(next_delete_at, utc_timestamp(3)), updated_at = utc_timestamp(3) where storage_provider = ? and bucket = ? and object_key = ? and status in ('UPLOADING','ACTIVE')",
            audio.storageProvider(), audio.bucket(), audio.objectKey());
    }

    @Transactional
    public long openConnection(RuntimePrincipal principal)
    {
        Long oldEpoch = jdbcTemplate.query("select connection_epoch from s_session where id = ? and account_id = ? and application_id = ? and session_snapshot_id = ? and status = 'ACTIVE' and expires_at > utc_timestamp(3) for update",
            rs -> rs.next() ? rs.getLong(1) : null, principal.sessionId(), principal.accountId(), principal.applicationId(), principal.snapshotId());
        if (oldEpoch == null) throw unavailable();
        Long priorTurnId = jdbcTemplate.query("select active_turn_id from s_session where id=?",
            rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null, principal.sessionId());
        if (priorTurnId != null) stop(priorTurnId, "REPLACED");
        long epoch = oldEpoch + 1;
        jdbcTemplate.update("update s_session set connection_epoch = ?,updated_at = ?,revision = revision + 1 where id = ?", epoch, Instant.now(), principal.sessionId());
        return epoch;
    }

    public boolean currentConnection(RuntimePrincipal principal, long epoch)
    {
        Integer match = jdbcTemplate.queryForObject("select count(1) from s_session where id = ? and account_id = ? and application_id = ? and session_snapshot_id = ? and status = 'ACTIVE' and expires_at > utc_timestamp(3) and connection_epoch = ?",
            Integer.class, principal.sessionId(), principal.accountId(), principal.applicationId(), principal.snapshotId(), epoch);
        return match != null && match == 1;
    }

    public java.util.Map<String, Object> sessionState(RuntimePrincipal principal)
    {
        return jdbcTemplate.query("select s.status,s.expires_at,s.connection_epoch,s.active_turn_id,t.status,t.text_status,t.audio_status,t.playback_status "
                + "from s_session s left join s_turn t on t.id=s.active_turn_id where s.id=? and s.account_id=? and s.application_id=? and s.session_snapshot_id=?",
            rs -> {
                if (!rs.next() || !"ACTIVE".equals(rs.getString(1))) throw unavailable();
                java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
                state.put("sessionId", Long.toString(principal.sessionId()));
                state.put("applicationId", Long.toString(principal.applicationId()));
                state.put("status", rs.getString(1));
                state.put("expiresAt", rs.getTimestamp(2).toInstant().toString());
                state.put("connectionEpoch", Long.toString(rs.getLong(3)));
                state.put("effectiveScopes", principal.scopes());
                state.put("capabilities", java.util.List.of("speech.create", "turn.stop", "playback.report"));
                if (rs.getObject(4) == null) state.put("activeTurn", null);
                else state.put("activeTurn", java.util.Map.of("turnId", Long.toString(rs.getLong(4)),
                    "status", rs.getString(5), "textStatus", rs.getString(6), "audioStatus", rs.getString(7),
                    "playbackStatus", rs.getString(8)));
                return state;
            }, principal.sessionId(), principal.accountId(), principal.applicationId(), principal.snapshotId());
    }

    public java.util.Map<String, Object> turnState(RuntimePrincipal principal, String turnId)
    {
        return jdbcTemplate.query("select t.status,t.text_status,t.audio_status,t.playback_status from s_turn t " +
                "where t.id=? and t.session_id=? and t.account_id=?",
            rs -> rs.next() ? java.util.Map.of("status", rs.getString(1), "textStatus", rs.getString(2),
                "audioStatus", rs.getString(3), "playbackStatus", rs.getString(4)) : java.util.Map.of(),
            turnId, principal.sessionId(), principal.accountId());
    }

    public boolean activeSession(RuntimePrincipal principal)
    {
        Integer count = jdbcTemplate.queryForObject("select count(1) from s_session where id=? and account_id=? " +
            "and application_id=? and session_snapshot_id=? and status='ACTIVE' and expires_at>utc_timestamp(3)",
            Integer.class, principal.sessionId(), principal.accountId(), principal.applicationId(), principal.snapshotId());
        return count != null && count == 1;
    }

    public TemporaryAudioReference readableAudio(RuntimePrincipal principal, String mediaId)
    {
        TemporaryAudioReference audio = jdbcTemplate.query("select o.storage_provider,o.bucket,o.object_key,o.expires_at from s_temp_object o join s_session s on s.id=o.session_id "
                + "where o.session_id = ? and o.account_id = ? and o.media_id = ? and o.purpose = 'TTS_AUDIO' and o.status = 'ACTIVE' and o.expires_at > now(3) and s.status='ACTIVE' and o.turn_id=s.active_turn_id",
            rs -> rs.next() ? new TemporaryAudioReference(mediaId, rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()) : null,
            principal.sessionId(), principal.accountId(), mediaId);
        if (audio == null) throw new RuntimeProblem(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Audio is unavailable.");
        return audio;
    }

    public List<TemporaryAudioReference> cleanupCandidates(int limit)
    {
        return jdbcTemplate.query("select media_id,storage_provider,bucket,object_key,expires_at from s_temp_object where "
                + "(status = 'DELETE_PENDING' and (delete_attempts = 0 or next_delete_at is null or next_delete_at <= utc_timestamp(3))) "
                + "or (status = 'ACTIVE' and expires_at <= now(3)) order by updated_at asc limit ?",
            (rs, row) -> new TemporaryAudioReference(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), limit);
    }

    public void markDeleted(TemporaryAudioReference audio)
    {
        jdbcTemplate.update("update s_temp_object set status = 'DELETED',updated_at = ?,next_delete_at = null,last_error_code = null where media_id = ? and storage_provider = ? and bucket = ? and object_key = ? and status in ('ACTIVE','DELETE_PENDING')",
            Instant.now(), audio.mediaId(), audio.storageProvider(), audio.bucket(), audio.objectKey());
    }

    public void markDeleteFailed(TemporaryAudioReference audio, String code)
    {
        jdbcTemplate.update("update s_temp_object set status='DELETE_PENDING',delete_attempts=delete_attempts+1,next_delete_at=date_add(utc_timestamp(3),interval least(300,5 * pow(2,least(5,delete_attempts))) second),last_error_code=?,updated_at=utc_timestamp(3) where media_id=? and storage_provider=? and bucket=? and object_key=? and status in ('ACTIVE','DELETE_PENDING')",
            code, audio.mediaId(), audio.storageProvider(), audio.bucket(), audio.objectKey());
    }

    private long nextId()
    {
        Long id = jdbcTemplate.queryForObject("select uuid_short()", Long.class);
        if (id == null || id <= 0)
        {
            throw new IllegalStateException("Could not allocate a session runtime identifier");
        }
        return id;
    }

    private long accountForTurn(long turnId)
    {
        Long account = jdbcTemplate.queryForObject("select account_id from s_turn where id=?", Long.class, turnId);
        if (account == null) throw new IllegalStateException("TTS operation no longer has an account");
        return account;
    }

    private void callOutbox(long accountId, long turnId, String segmentId, long operationId, String status,
        Long inputChars, Long audioDurationMs, String errorCode, String providerRequestId)
    {
        if (segmentId == null || segmentId.isBlank()) return;
        String eventId = UUID.randomUUID().toString().replace("-", "");
        long[] owner = jdbcTemplate.query("select s.application_id,t.session_id from s_turn t join s_session s on s.id=t.session_id " +
            "where t.id=? and t.account_id=?", rs -> rs.next() ? new long[] { rs.getLong(1), rs.getLong(2) } : null,
            turnId, accountId);
        if (owner == null) throw new IllegalStateException("TTS turn owner is unavailable");
        Long quotaId = jdbcTemplate.query("select quota_reservation_id from s_operation where id=?",
            rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null, operationId);
        jdbcTemplate.update("insert into s_outbox (id,created_at,updated_at,account_id,event_id,event_type,aggregate_type,aggregate_id,schema_version,trace_id,payload,status,attempt_count,next_run_at) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,1,?,json_object('eventId',?,'operationKey',?,'accountId',?,'applicationId',?,'sessionId',?,'quotaReservationId',?,'capability','TTS','status',?,'turnId',?,'usage',json_object('inputChars',?,'audioDurationMs',?,'usageAvailable',?),'costSource','UNKNOWN','errorCode',?,'providerRequestId',?),'PENDING',0,utc_timestamp(3))",
            accountId, eventId, "CALL_FACT_RECORDED", "TTS_OPERATION", Long.toString(operationId), eventId, eventId,
            "tts:" + turnId + ":" + segmentId, accountId, owner[0], owner[1], quotaId, status, turnId, inputChars, audioDurationMs,
            "SUCCEEDED".equals(status), errorCode, providerRequestId);
    }

    private record Operation(long id, long inputChars, String segmentId) { }

    private static byte[] hash(String value)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static RuntimeProblem unavailable()
    {
        return new RuntimeProblem(HttpStatus.CONFLICT, "SESSION_NOT_READY", "The DEBUG Session is not active.");
    }
}
