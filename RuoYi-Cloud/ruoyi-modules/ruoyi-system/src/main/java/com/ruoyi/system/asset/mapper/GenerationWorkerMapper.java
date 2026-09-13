package com.ruoyi.system.asset.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.asset.domain.ClaimedGenerationStep;
import com.ruoyi.system.asset.domain.OutboxEvent;

/** 受租约保护的制作 Worker 持久化边界。 */
public interface GenerationWorkerMapper
{
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

    int updateStepSuccess(@Param("accountId") Long accountId, @Param("taskId") Long taskId, @Param("stepId") Long stepId,
        @Param("workerId") String workerId, @Param("leaseEpoch") Long leaseEpoch, @Param("fileId") Long fileId,
        @Param("metadata") String metadata);

    int updateAttemptSuccess(@Param("attemptId") Long attemptId, @Param("stepId") Long stepId,
        @Param("attemptNo") Integer attemptNo, @Param("leaseEpoch") Long leaseEpoch, @Param("fileId") Long fileId);

    int countUnsucceededSteps(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    OutboxEvent selectPendingOutboxForUpdate();
    int claimOutbox(@Param("id") Long id, @Param("workerId") String workerId);
    int markOutboxSent(@Param("id") Long id, @Param("workerId") String workerId);
}
