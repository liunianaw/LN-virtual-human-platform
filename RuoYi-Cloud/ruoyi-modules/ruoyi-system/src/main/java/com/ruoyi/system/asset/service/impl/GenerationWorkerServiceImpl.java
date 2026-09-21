package com.ruoyi.system.asset.service.impl;

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
import com.ruoyi.system.asset.dto.GenerationStoredObject;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.mapper.GenerationWorkerMapper;
import com.ruoyi.system.asset.service.IGenerationWorkerService;
import com.ruoyi.system.operations.OperationsService;
import com.ruoyi.system.storage.ObjectStorage;

/** ruoyi-media GenerationPlatform/ObjectWriter ports 的平台侧租约实现。 */
@Service
public class GenerationWorkerServiceImpl implements IGenerationWorkerService
{
    private final GenerationWorkerMapper workerMapper;
    private final AssetMapper assetMapper;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final OperationsService callFacts;

    public GenerationWorkerServiceImpl(GenerationWorkerMapper workerMapper, AssetMapper assetMapper,
        ObjectProvider<ObjectStorage> storageProvider,
        TransactionTemplate transactions, ObjectMapper objectMapper, OperationsService callFacts)
    {
        this.workerMapper = workerMapper;
        this.assetMapper = assetMapper;
        this.storageProvider = storageProvider;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.callFacts = callFacts;
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
            int attempt = step.getAttemptNo() + ("READY".equals(step.getStepStatus()) ? 1 : 0);
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
            response.put("officialServiceId", step.getOfficialServiceId());
            response.put("serviceRevision", step.getServiceRevision());
            response.put("referenceUrl", storage.readUrl(step.getSourceObjectKey()));
            response.put("outputPrefix", "avatar-generation/" + accountId + "/" + taskId + "/" + step.getStepId() + "/" + epoch);
            return response;
        });
    }

    public Map<String, Object> prepareAttempt(Long accountId, Long taskId, Long stepId, String workerId, Long leaseEpoch,
        String requestHash)
    {
        validateIdentity(accountId, taskId, workerId);
        if (stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0 || requestHash == null || !requestHash.matches("[0-9a-fA-F]{64}"))
            throw new ServiceException("Worker attempt 参数无效");
        return transactions.execute(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            if (claim.getExistingAttemptId() != null)
            {
                if (workerMapper.resumeAttempt(claim.getExistingAttemptId(), stepId, leaseEpoch,
                    java.util.HexFormat.of().parseHex(requestHash)) != 1) throw staleLease();
                workerMapper.upsertLatestAttempt(claim, claim.getExistingAttemptId());
                Map<String, Object> resumed = new LinkedHashMap<>();
                resumed.put("attemptId", claim.getExistingAttemptId());
                resumed.put("providerRequestKey", claim.getProviderRequestKey());
                resumed.put("recoveryOnly", true);
                resumed.put("receiptJson", claim.getReceiptJson());
                return resumed;
            }
            Long attemptId = claim.getReservedAttemptId() == null ? nextId() : claim.getReservedAttemptId();
            String providerRequestKey = UUID.randomUUID().toString().replace("-", "");
            workerMapper.insertAttempt(attemptId, claim, providerRequestKey, java.util.HexFormat.of().parseHex(requestHash));
            workerMapper.upsertLatestAttempt(claim, attemptId);
            callFacts.generation(attemptId, accountId, "STARTED", null, null);
            return Map.of("attemptId", attemptId, "providerRequestKey", providerRequestKey, "recoveryOnly", false);
        });
    }

    public void progress(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        String state, String providerRequestId, String errorCode)
    {
        validateIdentity(accountId, taskId, workerId);
        if (attemptId == null || attemptId <= 0 || stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0
            || !("RUNNING".equals(state) || "POLLING".equals(state) || "UNKNOWN".equals(state) || "FAILED".equals(state)))
            throw new ServiceException("Worker progress 参数无效");
        transactions.executeWithoutResult(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            String attemptStatus = ("RUNNING".equals(state) || "POLLING".equals(state)) ? "SUBMITTED" : state;
            if (workerMapper.updateAttemptProgress(attemptId, stepId, claim.getAttemptNo(), leaseEpoch, attemptStatus,
                safeToken(providerRequestId, 128), safeToken(errorCode, 64)) != 1
                || workerMapper.updateStepProgress(accountId, taskId, stepId, workerId, leaseEpoch, state, safeToken(errorCode, 64)) != 1)
                throw staleLease();
            callFacts.generation(attemptId, accountId, "UNKNOWN".equals(state) || "FAILED".equals(state) ? state : "STARTED",
                providerRequestId, errorCode);
        });
    }

    public void saveReceipt(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId,
        Long leaseEpoch, Map<String, Object> receipt)
    {
        validateIdentity(accountId, taskId, workerId);
        if (receipt == null || attemptId == null || stepId == null || leaseEpoch == null)
            throw new ServiceException("Worker 回执参数无效");
        // Only protocol recovery fields; never accept arbitrary credential-bearing maps.
        if (!java.util.Set.of("providerTaskId", "requestId", "stage", "endpoint", "imageUrl", "usage", "errorCode", "submittedAt", "recoveryFailures").containsAll(receipt.keySet())
            || json(receipt).length() > 32768) throw new ServiceException("Worker 回执字段无效");
        String providerTaskId = receipt.get("providerTaskId") instanceof String value ? safeToken(value, 128) : null;
        String requestId = receipt.get("requestId") instanceof String value ? safeToken(value, 128) : null;
        if (providerTaskId == null && requestId == null) throw new ServiceException("Worker 回执缺少有效标识");
        transactions.executeWithoutResult(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            if (!attemptId.equals(claim.getExistingAttemptId())) throw staleLease();
            if (workerMapper.saveProviderReceipt(attemptId, stepId, leaseEpoch, providerTaskId, requestId) != 1
                || workerMapper.saveStepReceipt(stepId, leaseEpoch, json(receipt)) != 1) throw staleLease();
        });
    }

    public void submitSucceeded(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        List<GenerationStoredObject> objects, Map<String, Object> manifest)
    {
        validateIdentity(accountId, taskId, workerId);
        if (attemptId == null || attemptId <= 0 || stepId == null || stepId <= 0 || leaseEpoch == null || leaseEpoch <= 0
            || objects == null || objects.isEmpty()) throw new ServiceException("Worker result 参数无效");
        transactions.executeWithoutResult(status -> {
            ClaimedGenerationStep claim = currentLease(accountId, taskId, stepId, workerId, leaseEpoch);
            if (manifest == null || !claim.getActionCode().equals(manifest.get("action"))
                || !(manifest.get("frames") instanceof List<?> frames) || frames.size() != 6
                || !(manifest.get("frameCount") instanceof Number count) || count.intValue() != 6)
                throw new ServiceException("Worker 动作布局不完整或动作不匹配");
            Map<String, AssetFile> files = registerObjects(claim, objects);
            AssetFile atlas = files.get("atlas.png");
            AssetFile manifestFile = files.get("manifest.json");
            if (atlas == null || manifestFile == null) throw new ServiceException("Worker 缺少动作图集或清单");
            Long primaryFileId = atlas.getId();
            workerMapper.insertActionResult(nextId(), claim, attemptId, atlas.getId(), manifestFile.getId(),
                json(manifest), atlas.getSha256());
            String metadata = json(Map.of("manifest", manifest == null ? Map.of() : manifest, "objectCount", objects.size()));
            if (workerMapper.updateStepSuccess(accountId, taskId, stepId, workerId, leaseEpoch, primaryFileId, metadata) != 1
                || workerMapper.updateAttemptSuccess(attemptId, stepId, claim.getAttemptNo(), leaseEpoch, primaryFileId) != 1)
                throw staleLease();
            workerMapper.updateTaskProgress(accountId, taskId);
            workerMapper.markTaskSucceeded(accountId, taskId);
            callFacts.generation(attemptId, accountId, "SUCCEEDED", null, null);
        });
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
            workerMapper.updateTaskProgress(accountId, taskId);
            workerMapper.markTaskFailed(accountId, taskId);
            callFacts.generation(attemptId, accountId, state, null, null);
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

    public boolean hasActiveSteps(Long accountId, Long taskId)
    {
        if (accountId == null || accountId <= 0 || taskId == null || taskId <= 0)
            throw new ServiceException("Worker task 参数无效");
        return workerMapper.countActiveSteps(accountId, taskId) > 0;
    }

    private Map<String, AssetFile> registerObjects(ClaimedGenerationStep claim, List<GenerationStoredObject> objects)
    {
        ObjectStorage storage = requireStorage();
        Map<String, AssetFile> files = new LinkedHashMap<>();
        String prefix = "avatar-generation/" + claim.getAccountId() + "/" + claim.getTaskId() + "/" + claim.getStepId() + "/" + claim.getLeaseEpoch() + "/";
        for (GenerationStoredObject object : objects)
        {
            if (object == null || object.objectKey() == null || !object.objectKey().startsWith(prefix) || object.objectKey().contains("..")
                || object.sha256() == null || !object.sha256().matches("[0-9a-fA-F]{64}") || object.sizeBytes() < 0
                || !("image/png".equals(object.contentType()) || "application/json".equals(object.contentType())))
                throw new ServiceException("Worker 输出对象不符合平台前缀或元数据约束");
            String name = object.objectKey().substring(prefix.length());
            AssetFile file = new AssetFile();
            file.setId(nextId()); file.setAccountId(claim.getAccountId());
            file.setPurpose("manifest.json".equals(name) ? "MANIFEST"
                : "atlas.png".equals(name) ? "ATLAS"
                : "frame-00.png".equals(name) ? "BASE" : "PREVIEW");
            file.setStorageProvider(storage.provider()); file.setBucket(storage.bucket()); file.setObjectKey(object.objectKey());
            file.setContentType(object.contentType()); file.setSizeBytes(object.sizeBytes()); file.setSha256(java.util.HexFormat.of().parseHex(object.sha256()));
            file.setStatus("AVAILABLE");
            assetMapper.insertFile(file);
            if (files.putIfAbsent(name, file) != null) throw new ServiceException("Worker 输出对象重复");
        }
        return files;
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
}
