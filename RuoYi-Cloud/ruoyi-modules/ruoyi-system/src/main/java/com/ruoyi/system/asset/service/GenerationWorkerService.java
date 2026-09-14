package com.ruoyi.system.asset.service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.constant.HttpStatus;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.asset.domain.ClaimedGenerationStep;
import com.ruoyi.system.asset.domain.OutboxEvent;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.mapper.GenerationWorkerMapper;
import com.ruoyi.system.storage.ObjectStorage;

/** ruoyi-media GenerationPlatform/ObjectWriter ports 的平台侧租约实现。 */
@Service
public class GenerationWorkerService
{
    private final GenerationWorkerMapper workerMapper;
    private final AssetMapper assetMapper;
    private final AvatarPublicationService publicationService;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public GenerationWorkerService(GenerationWorkerMapper workerMapper, AssetMapper assetMapper,
        AvatarPublicationService publicationService, ObjectProvider<ObjectStorage> storageProvider,
        TransactionTemplate transactions, ObjectMapper objectMapper)
    {
        this.workerMapper = workerMapper;
        this.assetMapper = assetMapper;
        this.publicationService = publicationService;
        this.storageProvider = storageProvider;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> claim(Long accountId, Long taskId, String workerId)
    {
        validateIdentity(accountId, taskId, workerId);
        return transactions.execute(status -> {
            workerMapper.releaseExpiredUnpreparedClaims(accountId, taskId, "CLAIM_PREFLIGHT_EXPIRED");
            ClaimedGenerationStep step = workerMapper.selectReadyActionForUpdate(accountId, taskId);
            if (step == null) return null;
            if (workerMapper.claimStep(step.getStepId(), workerId, step.getLeaseEpoch()) != 1) throw staleLease();
            workerMapper.startTask(accountId, taskId);
            long epoch = step.getLeaseEpoch() + 1;
            int attempt = step.getAttemptNo() + 1;
            ObjectStorage storage = requireStorage();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accountId", accountId);
            response.put("taskId", taskId);
            response.put("stepId", step.getStepId());
            response.put("action", step.getActionCode());
            response.put("attemptNo", attempt);
            response.put("leaseEpoch", epoch);
            response.put("leaseSeconds", 300);
            response.put("model", step.getModelId());
            response.put("parametersJson", step.getParameters() == null ? "{}" : step.getParameters());
            response.put("referenceUrl", storage.readUrl(step.getSourceObjectKey()));
            response.put("outputPrefix", "avatar-generation/" + accountId + "/" + taskId + "/" + step.getStepId() + "/" + epoch);
            return response;
        });
    }

    public OutboxEvent claimOutbox(String workerId)
    {
        if (safeToken(workerId, 128) == null) throw new ServiceException("Worker 身份无效");
        return transactions.execute(status -> {
            OutboxEvent event = workerMapper.selectPendingOutboxForUpdate();
            if (event == null) return null;
            if (workerMapper.claimOutbox(event.getId(), workerId) != 1) throw staleLease();
            return event;
        });
    }

    public void markOutboxSent(Long outboxId, String workerId)
    {
        if (outboxId == null || outboxId <= 0 || safeToken(workerId, 128) == null || workerMapper.markOutboxSent(outboxId, workerId) != 1) throw staleLease();
    }

    public Map<String, Object> prepareAttempt(Long accountId, Long taskId, Long stepId, String workerId, Long leaseEpoch,
        String requestHash)
    {
        validateIdentity(accountId, taskId, workerId);
        if (stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0 || requestHash == null || !requestHash.matches("[0-9a-fA-F]{64}"))
            throw new ServiceException("Worker attempt 参数无效");
        return transactions.execute(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            Long attemptId = nextId();
            String providerRequestKey = UUID.randomUUID().toString().replace("-", "");
            workerMapper.insertAttempt(attemptId, claim, providerRequestKey, java.util.HexFormat.of().parseHex(requestHash));
            return Map.of("attemptId", attemptId, "providerRequestKey", providerRequestKey);
        });
    }

    public void progress(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        String state, String providerRequestId, String errorCode)
    {
        validateIdentity(accountId, taskId, workerId);
        if (attemptId == null || attemptId <= 0 || stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0
            || !("RUNNING".equals(state) || "UNKNOWN".equals(state) || "FAILED".equals(state)))
            throw new ServiceException("Worker progress 参数无效");
        transactions.executeWithoutResult(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            String attemptStatus = "RUNNING".equals(state) ? "SUBMITTED" : state;
            if (workerMapper.updateAttemptProgress(attemptId, stepId, claim.getAttemptNo(), leaseEpoch, attemptStatus,
                safeToken(providerRequestId, 128), safeToken(errorCode, 64)) != 1
                || workerMapper.updateStepProgress(accountId, taskId, stepId, workerId, leaseEpoch, state, safeToken(errorCode, 64)) != 1)
                throw staleLease();
        });
    }

    public void submitSucceeded(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        List<StoredObject> objects, Map<String, Object> manifest)
    {
        validateIdentity(accountId, taskId, workerId);
        if (attemptId == null || attemptId <= 0 || stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0
            || objects == null || objects.isEmpty()) throw new ServiceException("Worker result 参数无效");
        transactions.executeWithoutResult(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            Long primaryFileId = registerObjects(claim, objects);
            String metadata = json(Map.of("manifest", manifest == null ? Map.of() : manifest, "objectCount", objects.size()));
            if (workerMapper.updateStepSuccess(accountId, taskId, stepId, workerId, leaseEpoch, primaryFileId, metadata) != 1
                || workerMapper.updateAttemptSuccess(attemptId, stepId, claim.getAttemptNo(), leaseEpoch, primaryFileId) != 1)
                throw staleLease();
        });
        if (workerMapper.countUnsucceededSteps(accountId, taskId) == 0)
        {
            try { publicationService.markGenerationReadyForReview(accountId, taskId); }
            catch (ServiceException ignored)
            {
                // 完整候选包尚未登记时保留在 PROCESSING，不把不完整结果标为 REVIEW。
            }
        }
    }

    /**
     * Completes an UNKNOWN/FAILED callback after its progress update.  The same
     * lease is required for both calls, then cleared so a stale callback can
     * never alter a later operator-approved attempt.
     */
    public void submitTerminal(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch, String state)
    {
        validateIdentity(accountId, taskId, workerId);
        if (attemptId == null || attemptId <= 0 || stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0
            || !("UNKNOWN".equals(state) || "FAILED".equals(state)))
            throw new ServiceException("Worker terminal result 参数无效");
        transactions.executeWithoutResult(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            if (workerMapper.finishTerminalStep(accountId, taskId, stepId, workerId, leaseEpoch, state) != 1
                || workerMapper.finishTerminalAttempt(attemptId, stepId, claim.getAttemptNo(), leaseEpoch, state) != 1)
                throw staleLease();
            workerMapper.markTaskFailed(accountId, taskId);
        });
    }

    public void releasePreflightClaim(Long accountId, Long taskId, Long stepId, String workerId, Long leaseEpoch, String errorCode)
    {
        validateIdentity(accountId, taskId, workerId);
        if (stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0 || safeToken(errorCode, 64) == null)
            throw new ServiceException("Worker preflight 参数无效");
        transactions.executeWithoutResult(status -> {
            if (workerMapper.releasePreflightClaim(accountId, taskId, stepId, workerId, leaseEpoch, errorCode) != 1)
                throw staleLease();
        });
    }

    private Long registerObjects(ClaimedGenerationStep claim, List<StoredObject> objects)
    {
        ObjectStorage storage = requireStorage();
        Long primary = null;
        String prefix = "avatar-generation/" + claim.getAccountId() + "/" + claim.getTaskId() + "/" + claim.getStepId() + "/" + claim.getLeaseEpoch() + "/";
        for (StoredObject object : objects)
        {
            if (object == null || object.objectKey() == null || !object.objectKey().startsWith(prefix) || object.objectKey().contains("..")
                || object.sha256() == null || !object.sha256().matches("[0-9a-fA-F]{64}") || object.sizeBytes() < 0
                || !("image/png".equals(object.contentType()) || "application/json".equals(object.contentType())))
                throw new ServiceException("Worker 输出对象不符合平台前缀或元数据约束");
            AssetFile file = new AssetFile();
            file.setId(nextId()); file.setAccountId(claim.getAccountId()); file.setPurpose(object.contentType().equals("application/json") ? "MANIFEST" : "ATLAS");
            file.setStorageProvider(storage.provider()); file.setBucket(storage.bucket()); file.setObjectKey(object.objectKey());
            file.setContentType(object.contentType()); file.setSizeBytes(object.sizeBytes()); file.setSha256(java.util.HexFormat.of().parseHex(object.sha256()));
            file.setStatus("AVAILABLE");
            assetMapper.insertFile(file);
            if (primary == null || object.objectKey().endsWith("/atlas.png")) primary = file.getId();
        }
        return primary;
    }

    private ClaimedGenerationStep currentLease(Long accountId, Long taskId, Long stepId, String workerId, Long leaseEpoch)
    {
        ClaimedGenerationStep claim = workerMapper.selectLeaseForUpdate(accountId, taskId, stepId, workerId, leaseEpoch);
        if (claim == null) throw staleLease();
        return claim;
    }

    private Long nextId() { Long id = assetMapper.nextId(); if (id == null || id <= 0) throw new ServiceException("无法生成业务标识"); return id; }
    private ObjectStorage requireStorage() { ObjectStorage storage = storageProvider.getIfAvailable(); if (storage == null) throw new ServiceException("云对象存储尚未配置"); return storage; }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new ServiceException("无法生成 Worker 结果元数据"); } }
    private static void validateIdentity(Long accountId, Long taskId, String workerId) { if (accountId == null || accountId <= 0 || taskId == null || taskId <= 0 || safeToken(workerId, 128) == null) throw new ServiceException("Worker 身份无效"); }
    private static String safeToken(String value, int limit) { return value != null && value.matches("[A-Za-z0-9._:-]{1," + limit + "}") ? value : null; }
    private static ServiceException staleLease() { return new ServiceException("STALE_LEASE", HttpStatus.CONFLICT); }

    public record StoredObject(String objectKey, String sha256, long sizeBytes, String contentType) { }
}
