package com.ruoyi.system.asset.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.constant.HttpStatus;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.asset.domain.AvatarActionRecord;
import com.ruoyi.system.asset.domain.AvatarRecord;
import com.ruoyi.system.asset.domain.AvatarVersionRecord;
import com.ruoyi.system.asset.dto.AvatarActionPreviewResponse;
import com.ruoyi.system.asset.dto.AvatarPreviewResponse;
import com.ruoyi.system.asset.dto.AvatarStatusReasonRequest;
import com.ruoyi.system.asset.dto.PublishAvatarVersionRequest;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.mapper.AvatarPublicationMapper;
import com.ruoyi.system.asset.lifecycle.PublicAssetLifecycleService;
import com.ruoyi.system.asset.service.IAvatarPublicationService;
import com.ruoyi.system.storage.ObjectStorage;
import com.ruoyi.common.security.utils.SecurityUtils;

/** 候选 Avatar 的 REVIEW、预览、人工发布和删除受限链路。 */
@Service
public class AvatarPublicationServiceImpl implements IAvatarPublicationService
{
    private static final Set<String> REQUIRED_ACTIONS = Set.of(
        "idle", "speaking", "listening", "thinking", "nod", "shake_head", "wave", "happy");

    private final AvatarPublicationMapper publicationMapper;
    private final AssetMapper assetMapper;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final PublicAssetLifecycleService lifecycle;

    public AvatarPublicationServiceImpl(AvatarPublicationMapper publicationMapper, AssetMapper assetMapper,
        ObjectProvider<ObjectStorage> storageProvider, TransactionTemplate transactionTemplate, ObjectMapper objectMapper,
        PublicAssetLifecycleService lifecycle)
    {
        this.publicationMapper = publicationMapper;
        this.assetMapper = assetMapper;
        this.storageProvider = storageProvider;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.lifecycle = lifecycle;
    }

    public AvatarPreviewResponse preview(Long accountId, Long avatarId, Long versionId)
    {
        requireAccount(accountId);
        AvatarVersionRecord version = publicationMapper.selectOwnedVersion(accountId, avatarId, versionId);
        if (version == null) version = publicationMapper.selectAccessiblePublishedVersion(accountId, avatarId, versionId);
        if (version == null) throw forbidden("无权访问此 Avatar 版本");
        if (!("REVIEW".equals(version.getVersionStatus()) || "PUBLISHED".equals(version.getVersionStatus())))
            throw new ServiceException("候选版本尚不可预览");
        validateCandidate(version);
        return previewResponse(version, accountId);
    }

    /** The session service obtains only the frozen, signed formal package; it never receives a storage credential. */
    public Map<String, Object> runtimePackage(Long accountId, Long avatarId, Long versionId)
    {
        requireAccount(accountId);
        AvatarVersionRecord version = publicationMapper.selectAccessiblePublishedVersion(accountId, avatarId, versionId);
        if (version == null || !"PUBLISHED".equals(version.getVersionStatus()) || !"PUBLISHED".equals(version.getAvatarStatus()))
            throw forbidden("无权访问此 Avatar 版本");
        validateCandidate(version);
        return packageDescriptor(version, accountId);
    }

    public AvatarPreviewResponse publish(Long accountId, Long avatarId, Long versionId, PublishAvatarVersionRequest request)
    {
        requireAccount(accountId);
        if (request == null || !Boolean.TRUE.equals(request.getVisualAccepted())
            || request.getReviewNote() == null || request.getReviewNote().trim().isEmpty()
            || request.getReviewNote().length() > 500)
            throw new ServiceException("必须确认人工视觉验收并填写不超过 500 字的验收备注");
        Map<String, Object> avatarAccess = publicationMapper.selectAccessibleAvatar(accountId, avatarId);
        if (avatarAccess == null || !asBoolean(avatarAccess.get("owned"))) throw forbidden("无权发布此 Avatar 版本");
        if ("OFFICIAL".equals(avatarAccess.get("visibility")) && !SecurityUtils.isAdmin())
            throw forbidden("只有管理员可以发布官方公共角色");
        transactionTemplate.executeWithoutResult(status -> {
            AvatarVersionRecord version = publicationMapper.selectOwnedVersionForUpdate(accountId, avatarId, versionId);
            if (version == null) throw forbidden("无权发布此 Avatar 版本");
            if ("PUBLISHED".equals(version.getVersionStatus())) return;
            if (!"REVIEW".equals(version.getVersionStatus())) throw new ServiceException("只有 REVIEW 候选版本可以发布");
            validateCandidate(version);
            publicationMapper.insertReview(nextId(), accountId, avatarId, versionId, request.getReviewNote().trim());
            if (publicationMapper.updateVersionToPublished(accountId, versionId, accountId) != 1
                || publicationMapper.updateAvatarCurrentVersion(accountId, avatarId, versionId) != 1)
                throw new ServiceException("Avatar 发布状态已变化，请重新查询");
            // reviewNote 属于人工备注，不进入资产版本快照、Outbox 或日志。
            writeOutbox(accountId, "AVATAR_VERSION_PUBLISHED", "AVATAR_VERSION", versionId.toString(), Map.of(
                "avatarId", avatarId, "avatarVersionId", versionId, "status", "PUBLISHED"));
        });
        return preview(accountId, avatarId, versionId);
    }

    /** 后续 Application/Session 绑定入口复用此约束，拒绝跨账号私有版本和未发布版本。 */
    public void requireOwnedPublishedVersion(Long accountId, Long avatarId, Long versionId)
    {
        requireAccount(accountId);
        AvatarVersionRecord version = publicationMapper.selectAccessiblePublishedVersion(accountId, avatarId, versionId);
        if (version == null || !"PUBLISHED".equals(version.getVersionStatus()) || !"PUBLISHED".equals(version.getAvatarStatus()))
            throw forbidden("无权引用此 Avatar 版本");
    }

    public Map<String, Object> listPublic(int pageNum, int pageSize)
    {
        int size = Math.max(1, Math.min(pageSize, 100));
        int page = Math.max(pageNum, 1);
        List<Map<String, Object>> items = publicationMapper.selectPublicAvatars((page - 1) * size, size);
        addPreviewUrls(items);
        return Map.of("items", items, "total", publicationMapper.countPublicAvatars(), "pageNum", page, "pageSize", size);
    }

    public Map<String, Object> listOwned(Long accountId, int pageNum, int pageSize, String status, String keyword)
    {
        requireAccount(accountId);
        int page = Math.max(1, pageNum), size = Math.max(1, Math.min(pageSize, 100));
        String state = isBlank(status) ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (state != null && !Set.of("DRAFT", "PUBLISHED", "UNLISTED", "DISABLED", "DELETING").contains(state))
            throw new ServiceException("角色状态筛选无效", HttpStatus.BAD_REQUEST);
        String query = isBlank(keyword) ? null : keyword.trim();
        if (query != null && query.length() > 100) throw new ServiceException("搜索关键词长度无效", HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> items = publicationMapper.selectOwnedAvatars(accountId, state, query, (page - 1) * size, size);
        addPreviewUrls(items);
        return Map.of("items", items, "total", publicationMapper.countOwnedAvatars(accountId, state, query), "pageNum", page, "pageSize", size);
    }

    public Map<String, Object> listAdminPublic(int pageNum, int pageSize, String status)
    {
        requireAdministrator();
        String filter = isBlank(status) ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (filter != null && !Set.of("DRAFT","PUBLISHED","UNLISTED","DISABLED","DELETING").contains(filter))
            throw new ServiceException("公共角色状态筛选无效", HttpStatus.BAD_REQUEST);
        int size = Math.max(1, Math.min(pageSize, 100));
        int page = Math.max(pageNum, 1);
        List<Map<String, Object>> items = publicationMapper.selectAdminPublicAvatars(filter, (page - 1) * size, size);
        addPreviewUrls(items);
        return Map.of("items", items, "total", publicationMapper.countAdminPublicAvatars(filter),
            "pageNum", page, "pageSize", size);
    }

    public Map<String, Object> detail(Long accountId, Long avatarId)
    {
        requireAccount(accountId);
        Map<String, Object> avatar = publicationMapper.selectAccessibleAvatar(accountId, avatarId);
        if (avatar == null) throw forbidden("无权访问此 Avatar");
        Map<String, Object> response = new java.util.LinkedHashMap<>(avatar);
        response.put("versions", publicationMapper.selectAccessibleAvatarVersions(accountId, avatarId));
        return response;
    }

    public Map<String, Object> references(Long accountId, Long avatarId)
    {
        requireAccount(accountId);
        if (publicationMapper.selectOwnedAvatarForUpdate(accountId, avatarId) == null) throw forbidden("无权访问此 Avatar");
        Map<String, Object> counts = publicationMapper.selectOwnedAvatarReferenceCounts(accountId, avatarId);
        return Map.of("counts", counts == null ? Map.of("applications", 0, "sessions", 0, "generations", 0) : counts,
            "applications", publicationMapper.selectOwnedAvatarApplications(accountId, avatarId));
    }

    public Map<String, Object> unpublish(Long operatorId, Long avatarId, AvatarStatusReasonRequest request)
    {
        requireAdministrator();
        return lifecycle.legacyStatusChange(operatorId, avatarId, request == null ? null : request.reason(), false);
    }

    public Map<String, Object> disable(Long operatorId, Long avatarId, AvatarStatusReasonRequest request)
    {
        requireAdministrator();
        return lifecycle.legacyStatusChange(operatorId, avatarId, request == null ? null : request.reason(), true);
    }

    public Map<String, Object> deleteAvatar(Long accountId, Long avatarId, String ifMatch, String idempotencyKey)
    {
        requireAccount(accountId);
        if (isBlank(idempotencyKey) || !idempotencyKey.matches("[\\x21-\\x7e]{1,64}"))
            throw new ServiceException("Idempotency-Key 无效", HttpStatus.BAD_REQUEST);
        return transactionTemplate.execute(status -> {
            String scope = digest("avatar:delete:" + avatarId);
            Map<String, Object> previous = publicationMapper.selectAssetIdempotencyForUpdate(accountId, scope, idempotencyKey);
            if (previous != null)
            {
                if (!MessageDigest.isEqual((byte[]) previous.get("requestHash"), digestBytes("delete")))
                    throw new ServiceException("同一 Idempotency-Key 的参数不同", HttpStatus.CONFLICT);
                return Map.of("avatarId", avatarId.toString(), "status", "DELETING");
            }
            AvatarRecord avatar = publicationMapper.selectOwnedAvatarForUpdate(accountId, avatarId);
            if (avatar == null) throw forbidden("无权删除此 Avatar");
            if (ifMatch == null || !Long.toString(avatar.getRevision()).equals(ifMatch.replace("\"", "").trim()))
                throw new ServiceException("角色已变化，请刷新后重试", 412);
            if ("DELETING".equals(avatar.getStatus())) return Map.of("avatarId", avatarId.toString(), "status", "DELETING");
            if ("DELETED".equals(avatar.getStatus())) return Map.of("avatarId", avatarId.toString(), "status", "DELETED");
            if (publicationMapper.countActiveReferences(avatarId) > 0
                || publicationMapper.countApplicationReferences(avatarId) > 0
                || publicationMapper.countRecoverableSessionReferences(avatarId) > 0
                || publicationMapper.countActiveGenerationTasks(accountId, avatarId) > 0)
                throw new ServiceException("Avatar 正被引用或制作中，不能删除", HttpStatus.CONFLICT);
            if (publicationMapper.markAvatarDeleting(accountId, avatarId, avatar.getRevision()) != 1)
                throw new ServiceException("Avatar 删除状态已变化，请重新查询");
            writeOutbox(accountId, "AVATAR_DELETE_REQUESTED", "AVATAR", avatarId.toString(), Map.of(
                "avatarId", avatarId, "status", "DELETING"));
            publicationMapper.insertAssetIdempotency(nextId(), accountId, scope, idempotencyKey, digestBytes("delete"), avatarId);
            return Map.of("avatarId", avatarId.toString(), "status", "DELETING");
        });
    }

    private void validateCandidate(AvatarVersionRecord version)
    {
        if (version.getBaseFileId() == null || version.getManifestFileId() == null || version.getPreviewFileId() == null
            || version.getFrameWidth() == null || version.getFrameWidth() <= 0 || version.getFrameHeight() == null || version.getFrameHeight() <= 0
            || version.getAnchorX() == null || version.getAnchorY() == null || !qaPassed(version.getQaReport())
            || publicationMapper.countUnavailableVersionFiles(version.getAccountId(), version.getId()) != 0)
            throw new ServiceException("候选版本的 QA、几何信息或正式文件尚未通过校验");
        List<AvatarActionRecord> actions = publicationMapper.selectActions(version.getId());
        if (actions.size() != REQUIRED_ACTIONS.size() || actions.stream().map(AvatarActionRecord::getActionCode).collect(java.util.stream.Collectors.toSet()).equals(REQUIRED_ACTIONS) == false
            || actions.stream().anyMatch(action -> action.getFrameCount() == null || action.getFrameCount() <= 0
                || action.getFps() == null || action.getFps().signum() <= 0 || action.getAtlasFileId() == null)
            || publicationMapper.countUnavailableActionFiles(version.getAccountId(), version.getId()) != 0)
            throw new ServiceException("候选版本未包含完整且可用的固定八动作");
    }

    private AvatarPreviewResponse previewResponse(AvatarVersionRecord version, Long requestingAccountId)
    {
        ObjectStorage storage = requireStorage();
        AvatarPreviewResponse response = new AvatarPreviewResponse();
        response.setAvatarId(version.getAvatarId());
        response.setVersionId(version.getId());
        response.setStatus(version.getVersionStatus());
        response.setFrameWidth(version.getFrameWidth());
        response.setFrameHeight(version.getFrameHeight());
        response.setAnchorX(version.getAnchorX());
        response.setAnchorY(version.getAnchorY());
        String expiresAt = storage.readUrlExpiresAt().toString();
        response.setExpiresAt(expiresAt);
        AssetFile baseFile = requireFile(version.getAccountId(), version.getBaseFileId());
        AssetFile previewFile = requireFile(version.getAccountId(), version.getPreviewFileId());
        String baseUrl = storage.readUrl(baseFile.getObjectKey());
        String previewUrl = storage.readUrl(previewFile.getObjectKey());
        response.setBaseImageUrl(baseUrl);
        response.setPreviewUrl(previewUrl);
        List<AvatarActionPreviewResponse> actions = new ArrayList<>();
        for (AvatarActionRecord action : publicationMapper.selectActions(version.getId()))
        {
            AssetFile atlas = requireFile(version.getAccountId(), action.getAtlasFileId());
            String atlasUrl = storage.readUrl(atlas.getObjectKey());
            AvatarActionPreviewResponse preview = new AvatarActionPreviewResponse();
            preview.setActionCode(action.getActionCode());
            preview.setFrameCount(action.getFrameCount());
            preview.setFps(action.getFps());
            preview.setLoopEnabled(action.getLoopEnabled());
            preview.setFrameLayout(action.getFrameLayout());
            preview.setAtlasUrl(atlasUrl);
            if (action.getPreviewFileId() != null) preview.setPreviewUrl(readUrl(storage, version.getAccountId(), action.getPreviewFileId()));
            preview.setExpiresAt(expiresAt);
            actions.add(preview);
        }
        response.setActions(actions);
        response.setManifestUrl(writeAccessManifest(storage, requestingAccountId, version, packageDescriptor(version, requestingAccountId)));
        return response;
    }

    private Map<String, Object> packageDescriptor(AvatarVersionRecord version, Long requestingAccountId)
    {
        try
        {
            ObjectStorage storage = requireStorage();
            String expiresAt = storage.readUrlExpiresAt().toString();
            AssetFile baseFile = requireFile(version.getAccountId(), version.getBaseFileId());
            AssetFile previewFile = requireFile(version.getAccountId(), version.getPreviewFileId());
            String baseUrl = storage.readUrl(baseFile.getObjectKey());
            String previewUrl = storage.readUrl(previewFile.getObjectKey());
            int width = version.getFrameWidth();
            int height = version.getFrameHeight();
            int anchorX = version.getAnchorX().multiply(java.math.BigDecimal.valueOf(width)).intValueExact();
            int anchorY = version.getAnchorY().multiply(java.math.BigDecimal.valueOf(height)).intValueExact();
            if (anchorX < 0 || anchorX > width || anchorY < 0 || anchorY > height)
                throw new ServiceException("Avatar 锚点超出画布范围");
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("packageType", "LN_AVATAR"); manifest.put("schemaVersion", 1);
            manifest.put("framing", "FULL_BODY"); manifest.put("lipSyncMode", "BASIC_SPEAKING");
            manifest.put("avatarId", version.getAvatarId().toString()); manifest.put("versionId", version.getId().toString());
            manifest.put("width", width); manifest.put("height", height);
            manifest.put("anchor", Map.of("x", anchorX, "y", anchorY));
            manifest.put("preview", assetDescriptor(previewFile, previewUrl, expiresAt));
            manifest.put("baseImage", assetDescriptor(baseFile, baseUrl, expiresAt));
            List<Map<String, Object>> actions = new ArrayList<>();
            for (AvatarActionRecord action : publicationMapper.selectActions(version.getId()))
            {
                AssetFile atlas = requireFile(version.getAccountId(), action.getAtlasFileId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", action.getActionCode()); item.put("frameCount", action.getFrameCount());
                item.put("fps", action.getFps()); item.put("loop", action.getLoopEnabled());
                item.put("atlas", assetDescriptor(atlas, storage.readUrl(atlas.getObjectKey()), expiresAt));
                item.put("frames", objectMapper.readTree(action.getFrameLayout()).get("frames"));
                actions.add(item);
            }
            manifest.put("actions", actions);
            return manifest;
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法生成短期 Avatar 访问清单"); }
    }

    private String writeAccessManifest(ObjectStorage storage, Long requestingAccountId, AvatarVersionRecord version,
        Map<String, Object> manifest)
    {
        try
        {
            byte[] bytes = objectMapper.writeValueAsBytes(manifest);
            String key = "avatar-access/" + requestingAccountId + "/" + version.getAvatarId() + "/"
                + version.getId() + "/manifest.json";
            storage.put(key, bytes, "application/json");
            return storage.readUrl(key);
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法生成短期 Avatar 访问清单"); }
    }

    private Map<String, Object> assetDescriptor(AssetFile file, String url, String expiresAt)
    {
        return Map.of("fileId", file.getId().toString(), "sha256", java.util.HexFormat.of().formatHex(file.getSha256()),
            "url", url, "expiresAt", expiresAt);
    }

    private AssetFile requireFile(Long accountId, Long fileId)
    {
        AssetFile file = publicationMapper.selectAvailableOwnedFile(accountId, fileId);
        if (file == null) throw new ServiceException("候选版本文件不可用");
        return file;
    }

    private boolean qaPassed(String qaReport)
    {
        try
        {
            JsonNode root = objectMapper.readTree(qaReport);
            return root != null && root.path("passed").asBoolean(false);
        }
        catch (Exception e) { return false; }
    }

    private String readUrl(ObjectStorage storage, Long accountId, Long fileId)
    {
        AssetFile file = publicationMapper.selectAvailableOwnedFile(accountId, fileId);
        if (file == null) throw new ServiceException("候选版本文件不可用");
        return storage.readUrl(file.getObjectKey());
    }

    private void writeOutbox(Long accountId, String eventType, String aggregateType, String aggregateId, Map<String, Object> payload)
    {
        Long id = assetMapper.nextId();
        if (id == null || id <= 0) throw new ServiceException("无法生成业务标识");
        String eventId = UUID.randomUUID().toString().replace("-", "");
        assetMapper.insertOutbox(id, accountId, eventId, eventType, aggregateType, aggregateId,
            UUID.randomUUID().toString().replace("-", ""), json(payload));
    }

    private void addPreviewUrls(List<Map<String, Object>> items)
    {
        ObjectStorage objects = requireStorage();
        for (Map<String, Object> item : items)
        {
            Object key = item.remove("previewObjectKey");
            item.put("previewUrl", key instanceof String value && !value.isBlank() ? objects.readUrl(value) : null);
        }
    }

    private Long nextId()
    {
        Long id = assetMapper.nextId();
        if (id == null || id <= 0) throw new ServiceException("无法生成业务标识");
        return id;
    }

    private String json(Map<String, Object> payload)
    {
        try { return objectMapper.writeValueAsString(payload); }
        catch (Exception e) { throw new ServiceException("无法生成发布事件"); }
    }

    private ObjectStorage requireStorage()
    {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) throw new ServiceException("云对象存储尚未配置");
        return storage;
    }

    private static void requireAdministrator()
    {
        if (!SecurityUtils.isAdmin()) throw forbidden("仅管理员可以执行此操作");
    }

    private static void requireAccount(Long accountId)
    {
        if (accountId == null || accountId <= 0) throw new ServiceException("当前登录账号无效");
    }

    private static ServiceException forbidden(String message)
    {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private static boolean asBoolean(Object value)
    {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }

    private static byte[] digestBytes(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 不可用", error); }
    }

    private static String digest(String value)
    {
        return java.util.HexFormat.of().formatHex(digestBytes(value));
    }
}
