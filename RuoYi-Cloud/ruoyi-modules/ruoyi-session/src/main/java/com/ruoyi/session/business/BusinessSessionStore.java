package com.ruoyi.session.business;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.runtime.RuntimeProblem;
import com.ruoyi.session.runtime.RuntimeTokenCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The session_db side of the BUSINESS Session state machine. No platform_db writes occur here. */
@Repository
public class BusinessSessionStore
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.ruoyi.session.runtime.ChatTurnStore chat;
    public BusinessSessionStore(JdbcTemplate jdbc, ObjectMapper json, com.ruoyi.session.runtime.ChatTurnStore chat)
    { this.jdbc = jdbc; this.json = json; this.chat = chat; }

    @Transactional
    public Preparation prepare(long accountId, long applicationId, long configId, String externalUserId, String key, byte[] requestHash)
    {
        Instant now = Instant.now();
        jdbc.update("insert into s_principal (id,created_at,updated_at,account_id,application_id,principal_type,external_user_id,status,auth_epoch,last_seen_at) " +
            "values (uuid_short(),?,?,?,?,'BUSINESS',?,'ACTIVE',1,?) on duplicate key update id=id",
            now, now, accountId, applicationId, externalUserId, now);
        Long principalId = jdbc.query("select id from s_principal where account_id=? and application_id=? and principal_type='BUSINESS' and external_user_id=? and status='ACTIVE' for update",
            rs -> rs.next() ? rs.getLong(1) : null, accountId, applicationId, externalUserId);
        if (principalId == null) throw problem(HttpStatus.FORBIDDEN, "PRINCIPAL_DISABLED");
        Preparation existing = jdbc.query("select s.id,s.account_id,s.application_id,s.principal_id,s.app_config_id,s.status,p.external_user_id,s.created_at,s.last_activity_at,s.expires_at,s.reference_operation_id,s.revision,s.create_request_hash " +
            "from s_session s join s_principal p on p.id=s.principal_id where s.account_id=? and s.application_id=? and s.principal_id=? and s.create_request_id=? for update",
            rs -> rs.next() ? new Preparation(session(rs), rs.getBytes(13), false) : null, accountId, applicationId, principalId, key);
        if (existing != null)
        {
            if ((existing.requestHash() == null || !MessageDigest.isEqual(existing.requestHash(), requestHash))
                || "DELETED".equals(existing.session().status()) || "FAILED".equals(existing.session().status()))
                throw problem(HttpStatus.CONFLICT, "SESSION_CREATE_CONFLICT");
            return existing;
        }
        long id = nextId();
        String operation = "business:" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into s_session (id,created_at,updated_at,account_id,application_id,principal_id,app_config_id,create_request_id,reference_operation_id,create_request_hash,status,auth_epoch,connection_epoch,next_turn_no,next_message_seq,last_activity_at,expires_at,revision) " +
            "values (?,?,?,?,?,?,?,?,?,?,'CREATING',1,0,1,1,?,?,1)",
            id, now, now, accountId, applicationId, principalId, configId, key, operation, requestHash, now, now.plus(2, ChronoUnit.HOURS));
        return new Preparation(find(id), requestHash, true);
    }

    @Transactional
    public void activate(long sessionId)
    {
        int changed = jdbc.update("update s_session set status='ACTIVE',updated_at=utc_timestamp(3),revision=revision+1 where id=? and status='CREATING' and expires_at>utc_timestamp(3)", sessionId);
        if (changed != 1)
        {
            String status = jdbc.query("select status from s_session where id=?", rs -> rs.next() ? rs.getString(1) : null, sessionId);
            if (!"ACTIVE".equals(status)) throw problem(HttpStatus.CONFLICT, "SESSION_NOT_READY");
        }
    }

    public Session owned(long accountId, long applicationId, long sessionId, String externalUserId)
    {
        Session row = jdbc.query("select s.id,s.account_id,s.application_id,s.principal_id,s.app_config_id,s.status,p.external_user_id,s.created_at,s.last_activity_at,s.expires_at,s.reference_operation_id,s.revision " +
            "from s_session s join s_principal p on p.id=s.principal_id and p.principal_type='BUSINESS' where s.id=? and s.account_id=? and s.application_id=? and p.external_user_id=?",
            rs -> rs.next() ? session(rs) : null, sessionId, accountId, applicationId, externalUserId);
        if (row == null) throw problem(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
        return row;
    }

    public Session find(long sessionId)
    {
        return jdbc.query("select s.id,s.account_id,s.application_id,s.principal_id,s.app_config_id,s.status,p.external_user_id,s.created_at,s.last_activity_at,s.expires_at,s.reference_operation_id,s.revision " +
            "from s_session s join s_principal p on p.id=s.principal_id and p.principal_type='BUSINESS' where s.id=?",
            rs -> rs.next() ? session(rs) : null, sessionId);
    }

    @Transactional
    public IssuedGrant mint(Session identity, BusinessSystemClient.Snapshot snapshot, String key, List<String> scopes, RuntimeTokenCodec codec)
    {
        Session row = ownedForUpdate(identity);
        if (!"ACTIVE".equals(row.status()) || !row.expiresAt().isAfter(Instant.now()))
            throw problem(HttpStatus.CONFLICT, "SESSION_NOT_ACTIVE");
        String scope = hex("grant:" + row.id());
        byte[] hash = digest(String.join(",", scopes));
        Idempotency idem = jdbc.query("select request_hash,resource_id from s_api_idempotency where account_id=? and scope=? and request_id=? for update",
            rs -> rs.next() ? new Idempotency(rs.getBytes(1), rs.getLong(2)) : null, row.accountId(), scope, key);
        if (idem != null)
        {
            if (!MessageDigest.isEqual(idem.hash(), hash)) throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            Grant previous = grant(idem.resourceId());
            if (previous == null || !"ACTIVE".equals(previous.status()) || !previous.expiresAt().isAfter(Instant.now())
                || previous.sessionId() != row.id()) throw problem(HttpStatus.CONFLICT, "GRANT_EXPIRED");
            return issued(previous, codec);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expires = now.plus(15, ChronoUnit.MINUTES);
        if (expires.isAfter(row.expiresAt())) expires = row.expiresAt();
        long principalEpoch = jdbc.queryForObject("select auth_epoch from s_principal where id=? and status='ACTIVE' for update", Long.class, row.principalId());
        long sessionEpoch = jdbc.queryForObject("select auth_epoch from s_session where id=?", Long.class, row.id());
        long grantId = nextId();
        String jti = UUID.randomUUID().toString().replace("-", "");
        String signing = codec.currentKeyVersion();
        String scopeJson;
        try { scopeJson = json.writeValueAsString(scopes); }
        catch (Exception error) { throw new IllegalStateException(error); }
        jdbc.update("insert into s_session_grant (id,created_at,updated_at,account_id,session_id,principal_id,application_id,grant_source,issuer_key_id,token_id,scopes,account_epoch,application_epoch,principal_epoch,session_epoch,status,expires_at,issuer_key_epoch,signing_key_version) " +
            "values (?,?,?,?,?,?,?,'BUSINESS_KEY',?,?,cast(? as json),1,?,?,?,'ACTIVE',?,?,?)",
            grantId, now, now, row.accountId(), row.id(), row.principalId(), row.applicationId(), snapshot.keyId(), jti,
            scopeJson, snapshot.applicationEpoch(), principalEpoch, sessionEpoch, expires, snapshot.keyEpoch(), signing);
        jdbc.update("insert into s_api_idempotency (id,created_at,updated_at,account_id,scope,request_id,request_hash,resource_type,resource_id,status,expires_at) " +
            "values (uuid_short(),?,?,?,?,?,?,'SESSION_GRANT',?,'SUCCEEDED',?)",
            now, now, row.accountId(), scope, key, hash, grantId, now.plus(25, ChronoUnit.HOURS));
        return issued(new Grant(grantId, row.id(), jti, signing, "ACTIVE", now, expires, scopes,
            row.accountId(), row.applicationId(), row.configId(), snapshot.applicationEpoch(), principalEpoch, sessionEpoch), codec);
    }

    private Session ownedForUpdate(Session identity)
    {
        Session row = jdbc.query("select s.id,s.account_id,s.application_id,s.principal_id,s.app_config_id,s.status,p.external_user_id,s.created_at,s.last_activity_at,s.expires_at,s.reference_operation_id,s.revision " +
            "from s_session s join s_principal p on p.id=s.principal_id and p.principal_type='BUSINESS' where s.id=? and s.account_id=? and s.application_id=? and p.external_user_id=? for update",
            rs -> rs.next() ? session(rs) : null, identity.id(), identity.accountId(), identity.applicationId(), identity.externalUserId());
        if (row == null) throw problem(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
        return row;
    }

    @Transactional
    public Session beginClose(Session identity)
    {
        Session row = ownedForUpdate(identity);
        if ("DELETED".equals(row.status()) || "DELETING".equals(row.status())) return row;
        Instant now = Instant.now();
        jdbc.update("update s_session set status='DELETING',auth_epoch=auth_epoch+1,connection_epoch=connection_epoch+1,active_turn_id=null,deleted_at=?,updated_at=?,revision=revision+1 where id=?",
            now, now, row.id());
        jdbc.update("update s_turn set status='INTERRUPTED',text_status=if(text_status='RUNNING','INTERRUPTED',text_status)," +
            "audio_status=if(audio_status='RUNNING','INTERRUPTED',audio_status),playback_status=if(playback_status in ('WAITING','PLAYING'),'STOPPED',playback_status)," +
            "cancel_reason='REVOKED',ended_at=coalesce(ended_at,?),updated_at=? where session_id=? and status='RUNNING'",
            now, now, row.id());
        chat.unknownInFlight(row.id(), "SESSION_REVOKED");
        jdbc.update("update s_operation set status=if(status in ('QUEUED','RUNNING'),'CANCELLED',status)," +
            "playback_status=if(playback_status in ('WAITING','STARTED'),'STOPPED',playback_status)," +
            "finished_at=coalesce(finished_at,?),updated_at=? where session_id=? and (status in ('QUEUED','RUNNING') or playback_status in ('WAITING','STARTED'))",
            now, now, row.id());
        jdbc.update("update s_session_grant set status='REVOKED',revoked_at=?,updated_at=? where session_id=? and status='ACTIVE'", now, now, row.id());
        jdbc.update("update s_runtime_ticket set status='EXPIRED',updated_at=? where session_id=? and status='ACTIVE'", now, row.id());
        jdbc.update("update s_temp_object set status='DELETE_PENDING',next_delete_at=?,updated_at=? where session_id=? and status in ('UPLOADING','ACTIVE')", now, now, row.id());
        String eventId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into s_outbox (id,created_at,updated_at,account_id,event_id,event_type,aggregate_type,aggregate_id,schema_version,trace_id,payload,status,attempt_count,next_run_at) " +
            "values (uuid_short(),?,?,?,?, 'BUSINESS_SESSION_REVOKED','SESSION',?,1,?,json_object('sessionId',cast(? as char),'applicationId',cast(? as char)),'PENDING',0,?)",
            now, now, row.accountId(), eventId, Long.toString(row.id()), eventId, row.id(), row.applicationId(), now);
        return find(row.id());
    }

    @Transactional
    public void finishClose(long sessionId)
    {
        jdbc.update("update s_session set status='DELETED',deleted_at=coalesce(deleted_at,utc_timestamp(3)),updated_at=utc_timestamp(3),revision=revision+1 where id=? and status='DELETING'", sessionId);
        jdbc.update("update s_outbox set status='SENT',published_at=utc_timestamp(3),updated_at=utc_timestamp(3) where event_type='BUSINESS_SESSION_REVOKED' and aggregate_id=? and status='PENDING'", Long.toString(sessionId));
    }

    @Transactional
    public List<Session> beginUserClose(long accountId, long applicationId, String externalUserId)
    {
        Long principal = jdbc.query("select id from s_principal where account_id=? and application_id=? and principal_type='BUSINESS' and external_user_id=? for update",
            rs -> rs.next() ? rs.getLong(1) : null, accountId, applicationId, externalUserId);
        if (principal == null) return List.of();
        jdbc.update("update s_principal set auth_epoch=auth_epoch+1,updated_at=utc_timestamp(3) where id=?", principal);
        return jdbc.query("select s.id,s.account_id,s.application_id,s.principal_id,s.app_config_id,s.status,p.external_user_id,s.created_at,s.last_activity_at,s.expires_at,s.reference_operation_id,s.revision " +
            "from s_session s join s_principal p on p.id=s.principal_id where s.principal_id=? and s.status in ('ACTIVE','CREATING','DELETING')",
            (rs, index) -> session(rs), principal);
    }

    public List<Long> cleanupCandidates()
    {
        return jdbc.queryForList("select id from s_session where status in ('CREATING','DELETING','FAILED') or (status='ACTIVE' and expires_at<=utc_timestamp(3)) order by updated_at limit 100", Long.class);
    }

    /** Same action key may resume a partially completed cross-service close, but cannot change its parameters. */
    @Transactional
    public ActionClaim claimAction(long accountId, long sessionId, String action, String key, String parameters)
    {
        String scope = hex(action + ":" + sessionId);
        byte[] hash = digest(parameters);
        ActionClaim prior = jdbc.query("select request_hash,status,result_count,updated_at from s_api_idempotency where account_id=? and scope=? and request_id=? for update",
            rs -> rs.next() ? new ActionClaim(rs.getBytes(1), "SUCCEEDED".equals(rs.getString(2)), rs.getInt(3),
                rs.getTimestamp(4).toInstant()) : null, accountId, scope, key);
        if (prior != null)
        {
            if (!MessageDigest.isEqual(prior.hash(), hash)) throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            if (!prior.completed())
            {
                if (prior.updatedAt().isAfter(Instant.now().minusSeconds(60)))
                    throw problem(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS");
                jdbc.update("update s_api_idempotency set updated_at=utc_timestamp(3) where account_id=? and scope=? and request_id=?",
                    accountId, scope, key);
            }
            return prior;
        }
        Instant now = Instant.now();
        jdbc.update("insert into s_api_idempotency (id,created_at,updated_at,account_id,scope,request_id,request_hash,resource_type,resource_id,status,expires_at) " +
            "values (uuid_short(),?,?,?,?,?,?,'REVOCATION',?,'PROCESSING',?)",
            now, now, accountId, scope, key, hash, sessionId, now.plus(25, ChronoUnit.HOURS));
        return new ActionClaim(hash, false, 0, now);
    }

    public void finishAction(long accountId, long sessionId, String action, String key, int count)
    {
        jdbc.update("update s_api_idempotency set status='SUCCEEDED',result_count=?,updated_at=utc_timestamp(3) where account_id=? and scope=? and request_id=?",
            count, accountId, hex(action + ":" + sessionId), key);
    }

    @Transactional
    public void failCreate(long sessionId)
    {
        jdbc.update("update s_session set status='FAILED',deleted_at=utc_timestamp(3),updated_at=utc_timestamp(3),revision=revision+1 where id=? and status='CREATING'", sessionId);
    }

    @Transactional
    public void finishFailed(long sessionId)
    {
        jdbc.update("update s_session set status='DELETED',updated_at=utc_timestamp(3),revision=revision+1 where id=? and status='FAILED'", sessionId);
    }

    public VerifiedGrant verify(RuntimeTokenCodec.V2Claims claims)
    {
        Grant row = jdbc.query("select g.id,g.session_id,g.token_id,g.signing_key_version,g.status,g.created_at,g.expires_at,g.scopes,g.account_id,g.application_id,s.app_config_id,g.application_epoch,g.principal_epoch,g.session_epoch " +
            "from s_session_grant g join s_session s on s.id=g.session_id join s_principal p on p.id=g.principal_id " +
            "where g.token_id=? and g.grant_source='BUSINESS_KEY' and g.account_id=? and g.application_id=? and g.session_id=? and s.app_config_id=? " +
            "and s.status='ACTIVE' and s.expires_at>utc_timestamp(3) and p.principal_type='BUSINESS' and p.status='ACTIVE' and p.auth_epoch=g.principal_epoch and s.auth_epoch=g.session_epoch",
            rs -> rs.next() ? grant(rs) : null, claims.tokenId(), claims.accountId(), claims.applicationId(), claims.sessionId(), claims.configVersionId());
        if (row == null || !"ACTIVE".equals(row.status()) || !row.expiresAt().isAfter(Instant.now())
            || !row.createdAt().equals(claims.issuedAt()) || !row.expiresAt().equals(claims.expiresAt())
            || !row.signingVersion().equals(claims.keyVersion())) throw problem(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED");
        return new VerifiedGrant(row.scopes(), row.applicationEpoch());
    }

    @Transactional
    public void touchSuccessfulActivity(long sessionId)
    {
        Instant now = Instant.now();
        jdbc.update("update s_session set last_activity_at=?,expires_at=least(date_add(created_at,interval 24 hour),?),updated_at=?,revision=revision+1 " +
            "where id=? and status='ACTIVE' and expires_at>? ", now, now.plus(2, ChronoUnit.HOURS), now, sessionId, now);
    }

    private Grant grant(long id)
    {
        return jdbc.query("select g.id,g.session_id,g.token_id,g.signing_key_version,g.status,g.created_at,g.expires_at,g.scopes,g.account_id,g.application_id,s.app_config_id,g.application_epoch,g.principal_epoch,g.session_epoch " +
            "from s_session_grant g join s_session s on s.id=g.session_id where g.id=?",
            rs -> rs.next() ? grant(rs) : null, id);
    }

    private Grant grant(java.sql.ResultSet rs) throws java.sql.SQLException
    {
        List<String> scopes;
        try { scopes = json.readValue(rs.getString(8), new TypeReference<List<String>>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid stored grant scopes", error); }
        return new Grant(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), scopes, rs.getLong(9), rs.getLong(10),
            rs.getLong(11), rs.getLong(12), rs.getLong(13), rs.getLong(14));
    }

    private static Session session(java.sql.ResultSet rs) throws java.sql.SQLException
    {
        return new Session(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
            rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant(),
            rs.getTimestamp(10).toInstant(), rs.getString(11), rs.getLong(12));
    }

    private static IssuedGrant issued(Grant grant, RuntimeTokenCodec codec)
    {
        RuntimeTokenCodec.V2Claims claims = new RuntimeTokenCodec.V2Claims(grant.signingVersion(), grant.jti(),
            grant.accountId(), grant.applicationId(), grant.sessionId(), grant.configId(), "BUSINESS_KEY",
            grant.createdAt(), grant.expiresAt());
        return new IssuedGrant(codec.encodeV2(claims), grant.expiresAt(), grant.scopes());
    }

    private long nextId()
    {
        Long id = jdbc.queryForObject("select uuid_short()", Long.class);
        if (id == null || id <= 0) throw new IllegalStateException("Could not allocate a Session identifier");
        return id;
    }
    private static byte[] digest(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String hex(String value) { return java.util.HexFormat.of().formatHex(digest(value)); }
    private static RuntimeProblem problem(HttpStatus status, String code) { return new RuntimeProblem(status, code, code); }

    public record Preparation(Session session, byte[] requestHash, boolean created) { }
    public record Session(long id, long accountId, long applicationId, long principalId, long configId, String status,
        String externalUserId, Instant createdAt, Instant lastActivityAt, Instant expiresAt, String referenceOperationId, long revision) { }
    public record IssuedGrant(String token, Instant expiresAt, List<String> scopes) { }
    public record VerifiedGrant(List<String> scopes, long applicationEpoch) { }
    private record Idempotency(byte[] hash, long resourceId) { }
    public record ActionClaim(byte[] hash, boolean completed, int count, Instant updatedAt) { }
    private record Grant(long id, long sessionId, String jti, String signingVersion, String status, Instant createdAt,
        Instant expiresAt, List<String> scopes, long accountId, long applicationId, long configId, long applicationEpoch,
        long principalEpoch, long sessionEpoch) { }
}
