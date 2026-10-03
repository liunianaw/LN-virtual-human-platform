package com.ruoyi.system.asset.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.storage.ObjectStorage;
import com.ruoyi.system.asset.service.IAssetStorageQuotaService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single lifecycle kernel for public Avatars and Voices.  It never accepts
 * an object path from a caller and never reports DELETED before storage cleanup
 * has finished.
 */
@Service
public class PublicAssetLifecycleService
{
    private static final String AVATARS = "avatars";
    private static final String VOICES = "voices";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectProvider<ObjectStorage> storage;
    private final TransactionTemplate transactions;
    private final IAssetStorageQuotaService storageQuota;

    public PublicAssetLifecycleService(JdbcTemplate jdbc, ObjectMapper json, ObjectProvider<ObjectStorage> storage,
        TransactionTemplate transactions, IAssetStorageQuotaService storageQuota)
    {
        this.jdbc = jdbc;
        this.json = json;
        this.storage = storage;
        this.transactions = transactions;
        this.storageQuota = storageQuota;
    }

    public Map<String, Object> detail(String kind, long resourceId)
    {
        Asset asset = read(kind, resourceId, false);
        Map<String, Object> result = asset.result();
        result.put("etag", Long.toString(asset.revision()));
        return result;
    }

    public Map<String, Object> references(String kind, long resourceId, int pageNum, int pageSize)
    {
        requireKind(kind);
        read(kind, resourceId, false);
        int page = Math.max(1, pageNum), size = Math.max(1, Math.min(100, pageSize));
        String versionTable = AVATARS.equals(kind) ? "p_avatar_version" : "p_voice_version";
        String ownerColumn = AVATARS.equals(kind) ? "avatar_id" : "voice_id";
        List<Map<String, Object>> items = jdbc.query("select r.holder_type,r.holder_id,r.state from p_resource_reference r join "
                + versionTable + " v on v.id = r.resource_id where r.resource_type = ? and v." + ownerColumn
                + " = ? and r.state in ('RESERVED','CONFIRMED') order by r.holder_type,r.holder_id limit ? offset ?",
            (rs, row) -> Map.<String, Object>of("holderType", rs.getString(1), "holderId", Long.toString(rs.getLong(2)), "state", rs.getString(3)),
            resourceType(kind), resourceId, size, (page - 1) * size);
        Integer total = jdbc.queryForObject("select count(*) from p_resource_reference r join " + versionTable
                + " v on v.id = r.resource_id where r.resource_type = ? and v." + ownerColumn
                + " = ? and r.state in ('RESERVED','CONFIRMED')", Integer.class, resourceType(kind), resourceId);
        int applications = count(kind, resourceId, "APP_CURRENT"), sessions = count(kind, resourceId, "SESSION"), generations = count(kind, resourceId, "GENERATION");
        if (AVATARS.equals(kind))
        {
            Integer activeTasks = jdbc.queryForObject("select count(*) from p_generation_task where avatar_id=? and status in ('QUEUED','PROCESSING')", Integer.class, resourceId);
            generations += activeTasks == null ? 0 : activeTasks;
        }
        return Map.of("counts", Map.of("applications", applications, "sessions", sessions, "generations", generations,"voices",count(kind,resourceId,"VOICE_VERSION")),
            "items", items, "total", total == null ? 0 : total, "pageNum", page, "pageSize", size);
    }

    @Transactional
    public Map<String, Object> unpublish(long operatorId, String kind, long resourceId, String reason, String ifMatch, String key)
    { return mutate(operatorId, kind, resourceId, "unpublish", "UNLISTED", reason, ifMatch, key, false); }

    @Transactional
    public Map<String, Object> disable(long operatorId, String kind, long resourceId, String reason, boolean acknowledgeImpact,
        String ifMatch, String key)
    {
        if (!acknowledgeImpact) throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "必须确认停用会中断已有使用");
        return mutate(operatorId, kind, resourceId, "disable", "DISABLED", reason, ifMatch, key, false);
    }

    /** Compatibility adapter for the older /system/asset administrator routes. */
    @Transactional
    public Map<String, Object> legacyStatusChange(long operatorId, long resourceId, String reason, boolean disabled)
    {
        Asset asset = read(AVATARS, resourceId, true);
        return mutate(operatorId, AVATARS, resourceId, disabled ? "legacy-disable" : "legacy-unpublish",
            disabled ? "DISABLED" : "UNLISTED", reason, Long.toString(asset.revision()), UUID.randomUUID().toString().replace("-", ""), false);
    }

    @Transactional
    public Map<String, Object> requestDelete(long operatorId, String kind, long resourceId, String ifMatch, String key)
    { return mutate(operatorId, kind, resourceId, "delete", "DELETING", null, ifMatch, key, true); }

    @Transactional
    public Map<String, Object> retryCleanup(long operatorId, String kind, long resourceId, String ifMatch, String key)
    {
        requireKey(key); Asset asset = read(kind, resourceId, true); requireRevision(asset, ifMatch);
        Idempotent previous = existing(operatorId, scope(kind, resourceId, "cleanup-retry"), key, hash("retry"));
        if (previous != null) return operationResult(kind, resourceId, asset.status(), asset.revision());
        if (!"DELETING".equals(asset.status())) throw problem(HttpStatus.CONFLICT, "资产不在删除中");
        jdbc.update("update " + table(kind) + " set cleanup_status='PENDING',next_cleanup_at=utc_timestamp(3),last_cleanup_error_code=null,updated_at=utc_timestamp(3) where id=?", resourceId);
        record(operatorId, scope(kind, resourceId, "cleanup-retry"), key, hash("retry"), kind, resourceId);
        outbox(operatorId, eventPrefix(kind) + "_DELETE_RETRY_REQUESTED", kind.toUpperCase(), resourceId, Map.of("resourceId", Long.toString(resourceId)));
        return operationResult(kind, resourceId, "DELETING", asset.revision());
    }

    /** Called by the scheduler. A failed object deletion remains retryable and never changes logical status to DELETED. */
    public void runOneCleanup(String kind)
    {
        Asset asset = claimCleanup(kind);
        if (asset == null) return;
        try
        {
            ObjectStorage objects = storage.getIfAvailable();
            if (objects == null) throw new IllegalStateException("OBJECT_STORAGE_UNAVAILABLE");
            List<FileObject> files = deletableFiles(kind, asset.id());
            transactions.executeWithoutResult(status -> files.forEach(file -> jdbc.update("update p_file set status='DELETE_PENDING',updated_at=utc_timestamp(3) where id=? and status='AVAILABLE'", file.id())));
            for (FileObject file : files) objects.delete(file.objectKey());
            completeCleanup(kind, asset);
        }
        catch (Exception error)
        {
            failCleanup(kind, asset, error instanceof IllegalStateException ? error.getMessage() : "OBJECT_DELETE_FAILED");
        }
    }

    private Map<String, Object> mutate(long operatorId, String kind, long resourceId, String operation, String target,
        String reason, String ifMatch, String key, boolean delete)
    {
        requireKind(kind); requireKey(key); if (!delete && (reason == null || reason.isBlank() || reason.trim().length() > 500))
            throw problem(HttpStatus.BAD_REQUEST, "必须填写不超过 500 字的原因");
        String normalized = delete ? "" : reason.trim();
        byte[] requestHash = hash(operation + "|" + target + "|" + normalized);
        Idempotent previous = existing(operatorId, scope(kind, resourceId, operation), key, requestHash);
        if (previous != null) return operationResult(kind, resourceId, previous.status(), previous.revision());
        Asset asset = read(kind, resourceId, true); requireRevision(asset, ifMatch);
        if (target.equals(asset.status())) { record(operatorId, scope(kind, resourceId, operation), key, requestHash, kind, resourceId); return operationResult(kind, resourceId, target, asset.revision()); }
        if ("UNLISTED".equals(target) && !"PUBLISHED".equals(asset.status())) throw problem(HttpStatus.CONFLICT, "只有已发布资产可以普通下架");
        if ("DISABLED".equals(target) && !List.of("DRAFT", "PUBLISHED", "UNLISTED").contains(asset.status())) throw problem(HttpStatus.CONFLICT, "当前状态不能紧急停用");
        if (delete)
        {
            if (!List.of("DRAFT", "PUBLISHED", "UNLISTED", "DISABLED").contains(asset.status())) throw problem(HttpStatus.CONFLICT, "当前状态不能删除");
            Map<String, Object> refs = references(kind, resourceId, 1, 1);
            @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) refs.get("counts");
            if (counts.values().stream().anyMatch(value -> value > 0)) throw problem(HttpStatus.CONFLICT, "资产仍有引用");
            jdbc.update("update " + table(kind) + " set status='DELETING',cleanup_status='PENDING',next_cleanup_at=utc_timestamp(3),revision=revision+1,updated_at=utc_timestamp(3) where id=?", resourceId);
            outbox(operatorId, eventPrefix(kind) + "_DELETE_REQUESTED", kind.toUpperCase(), resourceId, Map.of("resourceId", Long.toString(resourceId), "status", "DELETING"));
        }
        else
        {
            jdbc.update("update " + table(kind) + " set status=?,revision=revision+1,updated_at=utc_timestamp(3) where id=?", target, resourceId);
            outbox(operatorId, eventPrefix(kind) + "_STATUS_CHANGED", kind.toUpperCase(), resourceId,
                Map.of("resourceId", Long.toString(resourceId), "previousStatus", asset.status(), "status", target, "reason", normalized));
        }
        record(operatorId, scope(kind, resourceId, operation), key, requestHash, kind, resourceId);
        return operationResult(kind, resourceId, target, asset.revision() + 1);
    }

    private Asset claimCleanup(String kind)
    {
        requireKind(kind);
        return transactions.execute(status -> {
            Asset asset = jdbc.query("select id,status,revision,cleanup_lease_epoch from " + table(kind) + " where status='DELETING' and cleanup_status in ('PENDING','FAILED') and (next_cleanup_at is null or next_cleanup_at <= utc_timestamp(3)) order by updated_at asc,id asc limit 1 for update",
                rs -> rs.next() ? new Asset(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) : null);
            if (asset == null) return null;
            int changed = jdbc.update("update " + table(kind) + " set cleanup_status='RUNNING',cleanup_lease_owner=?,cleanup_lease_epoch=cleanup_lease_epoch+1,cleanup_lease_expires_at=date_add(utc_timestamp(3),interval 5 minute),cleanup_attempt_count=cleanup_attempt_count+1,updated_at=utc_timestamp(3) where id=? and cleanup_lease_epoch=?", workerId(), asset.id(), asset.leaseEpoch());
            return changed == 1 ? new Asset(asset.id(), asset.status(), asset.revision(), asset.leaseEpoch() + 1) : null;
        });
    }

    private List<FileObject> deletableFiles(String kind, long resourceId)
    {
        String candidates = AVATARS.equals(kind)
            ? "select distinct f.id,f.object_key from p_file f join (select source_file_id file_id from p_avatar_version where avatar_id=? union select base_file_id from p_avatar_version where avatar_id=? union select manifest_file_id from p_avatar_version where avatar_id=? union select preview_file_id from p_avatar_version where avatar_id=? union select action.atlas_file_id from p_avatar_action action join p_avatar_version v on v.id=action.avatar_version_id where v.avatar_id=? union select action.preview_file_id from p_avatar_action action join p_avatar_version v on v.id=action.avatar_version_id where v.avatar_id=? union select result.atlas_file_id from p_avatar_action_result result join p_avatar_version v on v.id=result.avatar_version_id where v.avatar_id=? union select result.manifest_file_id from p_avatar_action_result result join p_avatar_version v on v.id=result.avatar_version_id where v.avatar_id=? union select f2.id from p_file f2 join p_generation_task t on t.account_id=f2.account_id and f2.object_key like concat('avatar-generation/',t.account_id,'/',t.id,'/%') where t.avatar_id=?) own on own.file_id=f.id where own.file_id is not null"
            : "select distinct f.id,f.object_key from p_file f join p_voice_version v on v.sample_file_id=f.id where v.voice_id=?";
        Object[] parameters = AVATARS.equals(kind) ? new Object[] {resourceId,resourceId,resourceId,resourceId,resourceId,resourceId,resourceId,resourceId,resourceId} : new Object[] {resourceId};
        return jdbc.query(candidates, (rs, row) -> new FileObject(rs.getLong(1), rs.getString(2)), parameters).stream()
            .filter(file -> !sharedOutsideTarget(kind, resourceId, file.id())).toList();
    }

    private boolean sharedOutsideTarget(String kind, long resourceId, long fileId)
    {
        Integer uses = jdbc.queryForObject(AVATARS.equals(kind)
                ? "select count(*) from (select avatar_id owner_id from p_avatar_version where source_file_id=? or base_file_id=? or manifest_file_id=? or preview_file_id=? union all select v.avatar_id from p_avatar_action a join p_avatar_version v on v.id=a.avatar_version_id where a.atlas_file_id=? or a.preview_file_id=? union all select -1 from p_voice_version where sample_file_id=?) uses where owner_id<>?"
                : "select count(*) from (select voice_id owner_id from p_voice_version where sample_file_id=? union all select -1 from p_avatar_version where source_file_id=? or base_file_id=? or manifest_file_id=? or preview_file_id=? union all select -1 from p_avatar_action where atlas_file_id=? or preview_file_id=?) uses where owner_id<>?",
            Integer.class, AVATARS.equals(kind) ? new Object[] {fileId,fileId,fileId,fileId,fileId,fileId,fileId,resourceId} : new Object[] {fileId,fileId,fileId,fileId,fileId,fileId,fileId,resourceId});
        if (uses != null && uses > 0) return true;
        Integer resultUses = jdbc.queryForObject("select count(*) from p_avatar_action_result r join p_avatar_version v on v.id=r.avatar_version_id where (r.atlas_file_id=? or r.manifest_file_id=?) and v.avatar_id<>?",
            Integer.class, fileId, fileId, AVATARS.equals(kind) ? resourceId : -1L);
        return resultUses != null && resultUses > 0;
    }

    protected void completeCleanup(String kind, Asset asset)
    {
        transactions.executeWithoutResult(status -> {
            for (FileObject file : deletableFiles(kind, asset.id()))
            {
                if (jdbc.update("update p_file set status='DELETED',delete_after=null,updated_at=utc_timestamp(3) where id=? and status in ('AVAILABLE','DELETE_PENDING')", file.id()) == 1)
                {
                    Long owner = jdbc.query("select account_id from p_file where id=? and storage_reservation_id is not null",
                        rs -> rs.next() ? rs.getLong(1) : null, file.id());
                    if (owner != null) storageQuota.free(owner, file.id());
                }
            }
            if (VOICES.equals(kind)) jdbc.update("update p_resource_reference r join p_voice_version v on v.id=r.holder_id set r.state='RELEASED',r.released_at=utc_timestamp(3) where r.holder_type='VOICE_VERSION' and v.voice_id=?",asset.id());
            jdbc.update("update " + table(kind) + " set status='DELETED',deleted_at=utc_timestamp(3),cleanup_status='COMPLETED',cleanup_lease_owner=null,cleanup_lease_expires_at=null,last_cleanup_error_code=null,revision=revision+1,updated_at=utc_timestamp(3) where id=? and status='DELETING' and cleanup_lease_epoch=?", asset.id(), asset.leaseEpoch());
        });
    }

    protected void failCleanup(String kind, Asset asset, String code)
    {
        transactions.executeWithoutResult(status -> jdbc.update("update " + table(kind) + " set cleanup_status='FAILED',cleanup_lease_owner=null,cleanup_lease_expires_at=null,next_cleanup_at=date_add(utc_timestamp(3),interval least(300, 5 * pow(2, least(5,cleanup_attempt_count))) second),last_cleanup_error_code=?,updated_at=utc_timestamp(3) where id=? and status='DELETING' and cleanup_lease_epoch=?", safeCode(code), asset.id(), asset.leaseEpoch()));
    }

    private Asset read(String kind, long id, boolean forUpdate)
    {
        requireKind(kind);
        Asset asset = jdbc.query("select id,status,revision,cleanup_lease_epoch from " + table(kind) + " where id=? and visibility='OFFICIAL' and status<>'DELETED'" + (forUpdate ? " for update" : ""),
            rs -> rs.next() ? new Asset(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) : null, id);
        if (asset == null) throw problem(HttpStatus.NOT_FOUND, "公共资产不存在"); return asset;
    }
    private int count(String kind, long id, String holder) { Integer value = jdbc.queryForObject("select count(*) from p_resource_reference r join " + (AVATARS.equals(kind) ? "p_avatar_version" : "p_voice_version") + " v on v.id=r.resource_id where r.resource_type=? and v." + (AVATARS.equals(kind) ? "avatar_id" : "voice_id") + "=? and r.holder_type=? and r.state in ('RESERVED','CONFIRMED')", Integer.class, resourceType(kind), id, holder); return value == null ? 0 : value; }
    private void outbox(long accountId, String type, String aggregate, long id, Map<String, Object> body) { try { jdbc.update("insert into p_outbox (id,created_at,updated_at,account_id,event_id,event_type,aggregate_type,aggregate_id,schema_version,trace_id,payload,status,attempt_count,next_run_at) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,1,?,cast(? as json),'PENDING',0,utc_timestamp(3))", accountId, UUID.randomUUID().toString().replace("-", ""), type, aggregate, Long.toString(id), UUID.randomUUID().toString().replace("-", ""), json.writeValueAsString(body)); } catch (Exception error) { throw new IllegalStateException("无法写入资产生命周期事件", error); } }
    private Idempotent existing(long account, String scope, String key, byte[] requestedHash) { return jdbc.query("select request_hash,resource_id from p_api_idempotency where account_id=? and scope=? and request_id=? for update", rs -> { if (!rs.next()) return null; byte[] stored=rs.getBytes(1); if (!MessageDigest.isEqual(stored,requestedHash)) throw problem(HttpStatus.CONFLICT,"幂等键参数冲突"); long resourceId=rs.getLong(2); Asset asset=read(scope.contains(AVATARS) ? AVATARS : VOICES,resourceId,false); return new Idempotent(asset.status(),asset.revision()); }, account,scope,key); }
    private void record(long account, String scope, String key, byte[] requestHash, String kind, long id) { jdbc.update("insert into p_api_idempotency (id,created_at,updated_at,account_id,scope,request_id,request_hash,resource_type,resource_id,status,expires_at) values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,?,?,'SUCCEEDED',date_add(utc_timestamp(3),interval 30 day))", account,scope,key,requestHash,resourceType(kind),id); }
    private Map<String,Object> operationResult(String kind,long id,String status,long revision) { return Map.of("resourceId",Long.toString(id),"status",status,"revision",Long.toString(revision),"etag",Long.toString(revision)); }
    private static String table(String kind) { requireKind(kind); return AVATARS.equals(kind) ? "p_avatar" : "p_voice"; }
    private static String resourceType(String kind) { requireKind(kind); return AVATARS.equals(kind) ? "AVATAR_VERSION" : "VOICE_VERSION"; }
    private static String eventPrefix(String kind) { return AVATARS.equals(kind) ? "AVATAR" : "VOICE"; }
    private static String scope(String kind,long id,String action) { return "public-"+kind+":"+id+":"+action; }
    private static void requireKind(String kind) { if (!AVATARS.equals(kind) && !VOICES.equals(kind)) throw problem(HttpStatus.BAD_REQUEST,"kind 仅支持 avatars 或 voices"); }
    private static void requireKey(String key) { if (key==null || !key.matches("[\\x21-\\x7e]{1,64}")) throw problem(HttpStatus.BAD_REQUEST,"Idempotency-Key 无效"); }
    private static void requireRevision(Asset asset,String value) { if (value==null || value.isBlank()) throw problem(HttpStatus.PRECONDITION_REQUIRED,"缺少 If-Match"); if (!Long.toString(asset.revision()).equals(value.replace("\"", ""))) throw problem(HttpStatus.PRECONDITION_FAILED,"资产修订已变化"); }
    private static ServiceException problem(HttpStatus status,String message) { return new ServiceException(message,status.value()); }
    private static byte[] hash(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String workerId() { return "public-asset-cleaner-" + java.lang.management.ManagementFactory.getRuntimeMXBean().getName(); }
    private static String safeCode(String code) { return code == null ? "OBJECT_DELETE_FAILED" : code.replaceAll("[^A-Z0-9_]", "_").substring(0, Math.min(64, code.length())); }
    private record Asset(long id,String status,long revision,long leaseEpoch) { Map<String,Object> result() { return new LinkedHashMap<>(Map.of("resourceId",Long.toString(id),"status",status,"revision",Long.toString(revision))); } }
    private record FileObject(long id,String objectKey) { }
    private record Idempotent(String status,long revision) { }
}
