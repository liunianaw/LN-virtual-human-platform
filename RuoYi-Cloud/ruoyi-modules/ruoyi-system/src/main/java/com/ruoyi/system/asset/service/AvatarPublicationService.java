package com.ruoyi.system.asset.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import com.ruoyi.system.asset.dto.PublishAvatarVersionRequest;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.mapper.AvatarPublicationMapper;
import com.ruoyi.system.storage.ObjectStorage;

/** 候选 Avatar 的 REVIEW、预览、人工发布和删除受限链路。 */
@Service
public class AvatarPublicationService
{
    private static final Set<String> REQUIRED_ACTIONS = Set.of(
        "idle", "speaking", "listening", "thinking", "nod", "shake_head", "wave", "happy");

    private final AvatarPublicationMapper publicationMapper;
    private final AssetMapper assetMapper;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public AvatarPublicationService(AvatarPublicationMapper publicationMapper, AssetMapper assetMapper,
        ObjectProvider<ObjectStorage> storageProvider, TransactionTemplate transactionTemplate, ObjectMapper objectMapper)
    {
        this.publicationMapper = publicationMapper;
        this.assetMapper = assetMapper;
        this.storageProvider = storageProvider;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 仅供同进程制作编排在产物持久化后调用；不暴露为用户可伪造的完成 HTTP 接口。
     */
    public void markGenerationReadyForReview(Long accountId, Long taskId)
    {
        requireAccount(accountId);
        transactionTemplate.executeWithoutResult(status -> {
            var task = assetMapper.selectTaskByAccountAndId(accountId, taskId);
            if (task == null) throw forbidden("无权更新此制作任务");
            AvatarVersionRecord version = publicationMapper.selectOwnedVersionForUpdate(accountId, task.getAvatarId(), task.getAvatarVersionId());
            if (version == null) throw forbidden("无权更新此候选版本");
            if ("REVIEW".equals(version.getVersionStatus()) && "SUCCEEDED".equals(task.getStatus())) return;
            if (!"BUILDING".equals(version.getVersionStatus()) || !("QUEUED".equals(task.getStatus()) || "PROCESSING".equals(task.getStatus())))
                throw new ServiceException("制作任务当前状态不能进入验收");
            validateCandidate(version);
            if (publicationMapper.updateVersionToReview(accountId, version.getId()) != 1
                || publicationMapper.updateTaskToReview(accountId, taskId, version.getId()) != 1)
                throw new ServiceException("制作任务状态已变化，请重新查询");
            writeOutbox(accountId, "AVATAR_REVIEW_READY", "GENERATION_TASK", taskId.toString(), Map.of(
                "taskId", taskId, "avatarId", version.getAvatarId(), "avatarVersionId", version.getId(), "status", "REVIEW"));
        });
    }

    public AvatarPreviewResponse preview(Long accountId, Long avatarId, Long versionId)
    {
        requireAccount(accountId);
        AvatarVersionRecord version = publicationMapper.selectOwnedVersion(accountId, avatarId, versionId);
        if (version == null) throw forbidden("无权访问此 Avatar 版本");
        if (!("REVIEW".equals(version.getVersionStatus()) || "PUBLISHED".equals(version.getVersionStatus())))
            throw new ServiceException("候选版本尚不可预览");
        validateCandidate(version);
        return previewResponse(version);
    }

    public AvatarPreviewResponse publish(Long accountId, Long avatarId, Long versionId, PublishAvatarVersionRequest request)
    {
        requireAccount(accountId);
        if (request == null || !Boolean.TRUE.equals(request.getVisualAccepted())
            || request.getReviewNote() == null || request.getReviewNote().trim().isEmpty()
            || request.getReviewNote().length() > 500)
            throw new ServiceException("必须确认人工视觉验收并填写不超过 500 字的验收备注");
        transactionTemplate.executeWithoutResult(status -> {
            AvatarVersionRecord version = publicationMapper.selectOwnedVersionForUpdate(accountId, avatarId, versionId);
            if (version == null) throw forbidden("无权发布此 Avatar 版本");
            if ("PUBLISHED".equals(version.getVersionStatus())) return;
            if (!"REVIEW".equals(version.getVersionStatus())) throw new ServiceException("只有 REVIEW 候选版本可以发布");
            validateCandidate(version);
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
        AvatarVersionRecord version = publicationMapper.selectOwnedVersion(accountId, avatarId, versionId);
        if (version == null || !"PUBLISHED".equals(version.getVersionStatus()) || !"PUBLISHED".equals(version.getAvatarStatus()))
            throw forbidden("无权引用此 Avatar 版本");
    }

    public void deleteAvatar(Long accountId, Long avatarId)
    {
        requireAccount(accountId);
        transactionTemplate.executeWithoutResult(status -> {
            AvatarRecord avatar = publicationMapper.selectOwnedAvatarForUpdate(accountId, avatarId);
            if (avatar == null) throw forbidden("无权删除此 Avatar");
            if ("DELETED".equals(avatar.getStatus()) || "DELETING".equals(avatar.getStatus())) return;
            if (publicationMapper.countActiveReferences(avatarId) > 0 || publicationMapper.countActiveGenerationTasks(accountId, avatarId) > 0)
                throw new ServiceException("Avatar 正被引用或制作中，不能删除", HttpStatus.CONFLICT);
            if (publicationMapper.markAvatarDeleting(accountId, avatarId) != 1)
                throw new ServiceException("Avatar 删除状态已变化，请重新查询");
            writeOutbox(accountId, "AVATAR_DELETE_REQUESTED", "AVATAR", avatarId.toString(), Map.of(
                "avatarId", avatarId, "status", "DELETING"));
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

    private AvatarPreviewResponse previewResponse(AvatarVersionRecord version)
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
        response.setBaseImageUrl(readUrl(storage, version.getAccountId(), version.getBaseFileId()));
        response.setManifestUrl(readUrl(storage, version.getAccountId(), version.getManifestFileId()));
        response.setPreviewUrl(readUrl(storage, version.getAccountId(), version.getPreviewFileId()));
        List<AvatarActionPreviewResponse> actions = new ArrayList<>();
        for (AvatarActionRecord action : publicationMapper.selectActions(version.getId()))
        {
            AvatarActionPreviewResponse preview = new AvatarActionPreviewResponse();
            preview.setActionCode(action.getActionCode());
            preview.setFrameCount(action.getFrameCount());
            preview.setFps(action.getFps());
            preview.setLoopEnabled(action.getLoopEnabled());
            preview.setFrameLayout(action.getFrameLayout());
            preview.setAtlasUrl(readUrl(storage, version.getAccountId(), action.getAtlasFileId()));
            if (action.getPreviewFileId() != null) preview.setPreviewUrl(readUrl(storage, version.getAccountId(), action.getPreviewFileId()));
            actions.add(preview);
        }
        response.setActions(actions);
        return response;
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

    private static void requireAccount(Long accountId)
    {
        if (accountId == null || accountId <= 0) throw new ServiceException("当前登录账号无效");
    }

    private static ServiceException forbidden(String message)
    {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }
}
