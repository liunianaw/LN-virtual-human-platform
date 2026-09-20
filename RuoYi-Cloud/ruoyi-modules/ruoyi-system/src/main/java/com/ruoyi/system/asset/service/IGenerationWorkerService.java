package com.ruoyi.system.asset.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.asset.dto.GenerationStoredObject;

/** 媒体 Worker 回调的租约、进度和结果处理服务。 */
public interface IGenerationWorkerService
{
    Map<String, Object> claim(Long accountId, Long taskId, String workerId);
    Map<String, Object> prepareAttempt(Long accountId, Long taskId, Long stepId, String workerId, Long leaseEpoch,
        String requestHash);
    void progress(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        String state, String providerRequestId, String errorCode);
    void saveReceipt(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        Map<String, Object> receipt);
    void submitSucceeded(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        List<GenerationStoredObject> objects, Map<String, Object> manifest);
    void submitTerminal(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
        String state);
    void releasePreflightClaim(Long accountId, Long taskId, Long stepId, String workerId, Long leaseEpoch,
        String errorCode);
    boolean hasActiveSteps(Long accountId, Long taskId);
}
