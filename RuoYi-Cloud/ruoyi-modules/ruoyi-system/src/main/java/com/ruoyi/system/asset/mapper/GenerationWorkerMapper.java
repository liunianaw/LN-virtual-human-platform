package com.ruoyi.system.asset.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.asset.domain.ClaimedGenerationStep;

/** 受租约保护的制作 Worker 持久化边界。 */
public interface GenerationWorkerMapper
{
    int insertActionResult(@Param("id") Long id, @Param("claim") ClaimedGenerationStep claim,
        @Param("attemptId") Long attemptId, @Param("atlasId") Long atlasId, @Param("manifestId") Long manifestId,
        @Param("manifest") String manifest, @Param("hash") byte[] hash);
    int upsertLatestAttempt(@Param("claim") ClaimedGenerationStep claim, @Param("attemptId") Long attemptId);
    int saveProviderReceipt(@Param("attemptId") Long attemptId, @Param("stepId") Long stepId,
        @Param("leaseEpoch") Long leaseEpoch, @Param("providerTaskId") String providerTaskId,
        @Param("providerRequestId") String providerRequestId);
    int saveStepReceipt(@Param("stepId") Long stepId, @Param("leaseEpoch") Long leaseEpoch,
        @Param("receipt") String receipt);
    int resumeAttempt(@Param("attemptId") Long attemptId, @Param("stepId") Long stepId,
        @Param("leaseEpoch") Long leaseEpoch, @Param("requestHash") byte[] requestHash);

    ClaimedGenerationStep selectReadyActionForUpdate(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    ClaimedGenerationStep selectLeaseForUpdate(@Param("accountId") Long accountId, @Param("taskId") Long taskId,
        @Param("stepId") Long stepId, @Param("workerId") String workerId, @Param("leaseEpoch") Long leaseEpoch);

    int claimStep(@Param("stepId") Long stepId, @Param("workerId") String workerId, @Param("leaseEpoch") Long leaseEpoch);

    int startTask(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    int insertAttempt(@Param("id") Long id, @Param("claim") ClaimedGenerationStep claim,
        @Param("providerRequestKey") String providerRequestKey, @Param("requestHash") byte[] requestHash);

    int updateAttemptProgress(@Param("attemptId") Long attemptId, @Param("stepId") Long stepId,
        @Param("attemptNo") Integer attemptNo, @Param("leaseEpoch") Long leaseEpoch, @Param("status") String status,
        @Param("providerRequestId") String providerRequestId, @Param("errorCode") String errorCode);

    int updateStepProgress(@Param("accountId") Long accountId, @Param("taskId") Long taskId, @Param("stepId") Long stepId,
        @Param("workerId") String workerId, @Param("leaseEpoch") Long leaseEpoch, @Param("status") String status,
        @Param("errorCode") String errorCode);

    int finishTerminalStep(@Param("accountId") Long accountId, @Param("taskId") Long taskId, @Param("stepId") Long stepId,
        @Param("workerId") String workerId, @Param("leaseEpoch") Long leaseEpoch, @Param("status") String status);

    int finishTerminalAttempt(@Param("attemptId") Long attemptId, @Param("stepId") Long stepId,
        @Param("attemptNo") Integer attemptNo, @Param("leaseEpoch") Long leaseEpoch, @Param("status") String status);

    int releasePreflightClaim(@Param("accountId") Long accountId, @Param("taskId") Long taskId, @Param("stepId") Long stepId,
        @Param("workerId") String workerId, @Param("leaseEpoch") Long leaseEpoch, @Param("errorCode") String errorCode);

    int releaseExpiredUnpreparedClaims(@Param("accountId") Long accountId, @Param("taskId") Long taskId, @Param("errorCode") String errorCode);

    int expireStalledUnsubmittedSteps(@Param("timeoutSeconds") int timeoutSeconds);

    int markTimedOutTasksFailed();

    int markTaskFailed(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    int updateTaskProgress(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    int markTaskSucceeded(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    int countActiveSteps(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    int updateStepSuccess(@Param("accountId") Long accountId, @Param("taskId") Long taskId, @Param("stepId") Long stepId,
        @Param("workerId") String workerId, @Param("leaseEpoch") Long leaseEpoch, @Param("fileId") Long fileId,
        @Param("metadata") String metadata);

    int updateAttemptSuccess(@Param("attemptId") Long attemptId, @Param("stepId") Long stepId,
        @Param("attemptNo") Integer attemptNo, @Param("leaseEpoch") Long leaseEpoch, @Param("fileId") Long fileId);

}
