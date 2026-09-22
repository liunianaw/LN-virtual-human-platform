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

    public PersistentRuntimeStore(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public long createSpeakTurn(RuntimePrincipal principal, String requestId, List<SegmentPlan> segments)
    {
        try
        {
            Long nextTurnNo = jdbcTemplate.queryForObject("select next_turn_no from s_session where id = ? and account_id = ? and application_id = ? and status = 'ACTIVE' for update",
                    Long.class, principal.sessionId(), principal.accountId(), principal.applicationId());
            if (nextTurnNo == null)
            {
                throw unavailable();
            }
            long turnId = nextId();
            Instant now = Instant.now();
            jdbcTemplate.update("update s_session set next_turn_no = ?, active_turn_id = ?, last_activity_at = ?, updated_at = ?, revision = revision + 1 where id = ?",
                    nextTurnNo + 1, turnId, now, now, principal.sessionId());
            jdbcTemplate.update("insert into s_turn (id,created_at,updated_at,account_id,session_id,turn_no,client_request_id,request_hash,turn_type,status,text_status,audio_status,playback_status,connection_epoch,input_source,include_in_history,last_event_seq,started_at) values (?,?,?,?,?,?,?,?, 'SPEAK','RUNNING','NOT_REQUESTED','RUNNING','WAITING',0,'TEXT',0,0,?)",
                    turnId, now, now, principal.accountId(), principal.sessionId(), nextTurnNo, requestId, hash(segments.stream().map(SegmentPlan::text).reduce("", String::concat)), now);
            for (SegmentPlan segment : segments)
            {
                long operationId = nextId();
                String operationRequestId = turnId + ":tts:" + segment.ordinal();
                jdbcTemplate.update("insert into s_operation (id,created_at,updated_at,account_id,session_id,turn_id,client_request_id,operation_type,ordinal,config_resource_id,status,playback_status,input_char_count,input_hash,result_summary) values (?,?,?,?,?,?,?,?,?,?,'QUEUED','WAITING',?,?,json_object('segmentId',?))",
                        operationId, now, now, principal.accountId(), principal.sessionId(), turnId, operationRequestId, "TTS", segment.ordinal(),
                        principal.voice().voiceVersionId(), (long) segment.text().codePointCount(0, segment.text().length()), hash(segment.text()), segment.segmentId());
                callOutbox(principal.accountId(), turnId, segment.segmentId(), operationId, "STARTED",
                    (long) segment.text().codePointCount(0, segment.text().length()), null, null, null);
            }
            return turnId;
        }
        catch (EmptyResultDataAccessException exception)
        {
            throw unavailable();
        }
    }

    @Transactional
    public void markAudioReady(long turnId, int ordinal, RuntimePrincipal principal, TemporaryAudioReference audio, long bytes, long durationMs)
    {
        Instant now = Instant.now();
        Operation operation = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) from s_operation where turn_id = ? and ordinal = ? and operation_type = 'TTS'",
            rs -> rs.next() ? new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null, turnId, ordinal);
        if (operation == null)
        {
            return;
        }
        jdbcTemplate.update("update s_operation set status = 'SUCCEEDED', result_summary = json_set(coalesce(result_summary,json_object()),'$.mediaId', ?), updated_at = ?, finished_at = ? where id = ? and status in ('QUEUED','RUNNING')",
                audio.mediaId(), now, now, operation.id());
        jdbcTemplate.update("insert into s_temp_object (id,media_id,created_at,updated_at,account_id,session_id,turn_id,operation_id,purpose,storage_provider,bucket,object_key,size_bytes,status,expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?)",
                nextId(), audio.mediaId(), now, now, principal.accountId(), principal.sessionId(), turnId, operation.id(), "TTS_AUDIO", audio.storageProvider(),
                audio.bucket(), audio.objectKey(), bytes, audio.expiresAt());
        callOutbox(principal.accountId(), turnId, operation.segmentId(), operation.id(), "SUCCEEDED", operation.inputChars(), durationMs, null, null);
    }

    @Transactional
    public void markAudioFailed(long turnId, int ordinal, String failureCode)
    {
        Instant now = Instant.now();
        jdbcTemplate.update("update s_operation set status = 'FAILED', result_summary = json_set(coalesce(result_summary,json_object()),'$.failureCode', ?), updated_at = ?, finished_at = ? where turn_id = ? and ordinal = ? and operation_type = 'TTS' and status in ('QUEUED','RUNNING')",
                failureCode, now, now, turnId, ordinal);
        Operation operation = jdbcTemplate.query("select id,input_char_count,json_unquote(json_extract(result_summary,'$.segmentId')) from s_operation where turn_id=? and ordinal=? and operation_type='TTS'",
            rs -> rs.next() ? new Operation(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null, turnId, ordinal);
        if (operation != null) callOutbox(accountForTurn(turnId), turnId, operation.segmentId(), operation.id(), "FAILED", operation.inputChars(), null, failureCode, null);
    }

    public void playback(long turnId, int ordinal, PlaybackState state)
    {
        String playback = state.name();
        jdbcTemplate.update("update s_operation set playback_status = ?, updated_at = ?, finished_at = case when ? in ('ENDED','FAILED','SKIPPED') then ? else finished_at end where turn_id = ? and ordinal = ? and operation_type = 'TTS' and playback_status not in ('ENDED','FAILED','SKIPPED','STOPPED')",
                playback, Instant.now(), playback, Instant.now(), turnId, ordinal);
    }

    public void stop(long turnId, String reason)
    {
        Instant now = Instant.now();
        jdbcTemplate.update("update s_operation set playback_status = 'STOPPED', updated_at = ?, finished_at = ? where turn_id = ? and operation_type = 'TTS' and playback_status not in ('ENDED','FAILED','SKIPPED','STOPPED')",
                now, now, turnId);
        jdbcTemplate.update("update s_turn set status = 'INTERRUPTED', audio_status = case when audio_status = 'COMPLETED' then 'COMPLETED' else 'INTERRUPTED' end, playback_status = case when playback_status in ('COMPLETED','FAILED') then playback_status else 'STOPPED' end, cancel_reason = ?, ended_at = coalesce(ended_at, ?), updated_at = ? where id = ? and status = 'RUNNING'",
                reason, now, now, turnId);
        jdbcTemplate.update("update s_session set active_turn_id=null,updated_at=?,revision=revision+1 where active_turn_id=?", now, turnId);
    }

    public void complete(long turnId)
    {
        Instant now = Instant.now();
        jdbcTemplate.update("update s_turn set status = 'COMPLETED', audio_status = 'COMPLETED', playback_status = 'COMPLETED', ended_at = ?, updated_at = ? where id = ? and status = 'RUNNING'", now, now, turnId);
        jdbcTemplate.update("update s_session set active_turn_id=null,updated_at=?,revision=revision+1 where active_turn_id=?", now, turnId);
    }

    public void fail(long turnId)
    {
        Instant now = Instant.now();
        jdbcTemplate.update("update s_turn set status = 'FAILED', audio_status = 'FAILED', playback_status = 'FAILED', ended_at = ?, updated_at = ? where id = ? and status = 'RUNNING'",
                now, now, turnId);
        jdbcTemplate.update("update s_session set active_turn_id=null,updated_at=?,revision=revision+1 where active_turn_id=?", now, turnId);
    }

    public void scheduleCleanup(TemporaryAudioReference audio)
    {
        jdbcTemplate.update("update s_temp_object set status = 'DELETE_PENDING', next_delete_at = coalesce(next_delete_at, ?), updated_at = ? where storage_provider = ? and bucket = ? and object_key = ? and status in ('UPLOADING','ACTIVE')",
                Instant.now(), Instant.now(), audio.storageProvider(), audio.bucket(), audio.objectKey());
    }

    @Transactional
    public long openConnection(RuntimePrincipal principal)
    {
        Long oldEpoch = jdbcTemplate.queryForObject("select connection_epoch from s_session where id = ? and account_id = ? and application_id = ? and app_config_id = ? and status = 'ACTIVE' for update",
            Long.class, principal.sessionId(), principal.accountId(), principal.applicationId(), principal.configVersionId());
        if (oldEpoch == null) throw unavailable();
        long epoch = oldEpoch + 1;
        jdbcTemplate.update("update s_session set connection_epoch = ?,last_activity_at = ?,updated_at = ?,revision = revision + 1 where id = ?", epoch, Instant.now(), Instant.now(), principal.sessionId());
        return epoch;
    }

    public boolean currentConnection(RuntimePrincipal principal, long epoch)
    {
        Integer match = jdbcTemplate.queryForObject("select count(1) from s_session where id = ? and account_id = ? and application_id = ? and app_config_id = ? and status = 'ACTIVE' and connection_epoch = ?",
            Integer.class, principal.sessionId(), principal.accountId(), principal.applicationId(), principal.configVersionId(), epoch);
        return match != null && match == 1;
    }

    public TemporaryAudioReference readableAudio(RuntimePrincipal principal, String mediaId)
    {
        TemporaryAudioReference audio = jdbcTemplate.query("select o.storage_provider,o.bucket,o.object_key,o.expires_at from s_temp_object o join s_session s on s.id=o.session_id "
                + "where o.session_id = ? and o.account_id = ? and o.media_id = ? and o.purpose = 'TTS_AUDIO' and o.status = 'ACTIVE' and o.expires_at > utc_timestamp(3) and s.status='ACTIVE' and o.turn_id=s.active_turn_id",
            rs -> rs.next() ? new TemporaryAudioReference(mediaId, rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()) : null,
            principal.sessionId(), principal.accountId(), mediaId);
        if (audio == null) throw new RuntimeProblem(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Audio is unavailable.");
        return audio;
    }

    public List<Long> expiredDebugRuntimeSessionIds()
    {
        return jdbcTemplate.queryForList("select s.id from s_session s where s.active_turn_id is not null and exists (select 1 from s_session_grant expired where expired.session_id = s.id and expired.grant_source = 'CONSOLE_DEBUG' and expired.status = 'ACTIVE' and expired.expires_at <= utc_timestamp(3)) and not exists (select 1 from s_session_grant active where active.session_id = s.id and active.grant_source = 'CONSOLE_DEBUG' and active.status = 'ACTIVE' and active.expires_at > utc_timestamp(3))",
                Long.class);
    }

    public List<TemporaryAudioReference> cleanupCandidates(int limit)
    {
        return jdbcTemplate.query("select media_id,storage_provider,bucket,object_key,expires_at from s_temp_object where "
                + "(status = 'DELETE_PENDING' and (next_delete_at is null or next_delete_at <= utc_timestamp(3))) "
                + "or (status = 'ACTIVE' and expires_at <= utc_timestamp(3)) order by updated_at asc limit ?",
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

    public void verifyConsoleGrant(TrustedConsoleDebugGrantClaims claims)
    {
        Integer matches = jdbcTemplate.queryForObject("select count(1) from s_session_grant g join s_session s on s.id = g.session_id join s_principal p on p.id = g.principal_id where g.token_id = ? and g.grant_source = 'CONSOLE_DEBUG' and g.status = 'ACTIVE' and g.expires_at > utc_timestamp(3) and g.issuer_console_ref = unhex(?) and g.account_id = ? and g.application_id = ? and g.session_id = ? and g.account_epoch = ? and g.application_epoch = ? and g.principal_epoch = ? and g.session_epoch = ? and s.status = 'ACTIVE' and s.auth_epoch = ? and p.status = 'ACTIVE' and p.auth_epoch = ?",
                Integer.class, claims.tokenId(), claims.issuerConsoleRef(), claims.accountId(), claims.applicationId(), claims.sessionId(), claims.accountEpoch(),
                claims.applicationEpoch(), claims.principalEpoch(), claims.sessionEpoch(), claims.sessionEpoch(), claims.principalEpoch());
        if (matches == null || matches != 1)
        {
            throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "The Console DEBUG Session Token is no longer valid.");
        }
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
        jdbcTemplate.update("insert into s_outbox (id,created_at,updated_at,account_id,event_id,event_type,aggregate_type,aggregate_id,schema_version,trace_id,payload,status,attempt_count,next_run_at) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,1,?,json_object('eventId',?,'operationKey',?,'accountId',?,'capability','TTS','status',?,'turnId',cast(? as char),'usage',json_object('inputChars',?,'audioDurationMs',?,'usageAvailable',?),'costSource','UNKNOWN','errorCode',?,'providerRequestId',?),'PENDING',0,utc_timestamp(3))",
            accountId, eventId, "CALL_FACT_RECORDED", "TTS_OPERATION", Long.toString(operationId), eventId, eventId,
            "tts:" + turnId + ":" + segmentId, accountId, status, turnId, inputChars, audioDurationMs,
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
