package com.ruoyi.system.asset.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.asset.domain.GenerationServiceConfig;
import com.ruoyi.system.asset.domain.GenerationTask;
import com.ruoyi.system.asset.dto.AvatarGenerationServiceResponse;

/** M2 资产基础账本 Mapper。所有账户范围由调用方显式传入。 */
public interface AssetMapper
{
    Long nextId();

    AssetFile selectAvailableFile(@Param("accountId") Long accountId, @Param("fileId") Long fileId);

    GenerationTask selectTaskByAccountAndRequest(@Param("accountId") Long accountId, @Param("requestId") String requestId);

    int countMatchingTaskRequest(@Param("accountId") Long accountId, @Param("taskId") Long taskId,
        @Param("sourceFileId") Long sourceFileId, @Param("officialServiceId") Long officialServiceId,
        @Param("name") String name, @Param("visibility") String visibility, @Param("requestHash") byte[] requestHash);

    GenerationTask selectTaskByAccountAndId(@Param("accountId") Long accountId, @Param("taskId") Long taskId);

    List<GenerationTask> selectRecentTasksByAccount(@Param("accountId") Long accountId);

    GenerationServiceConfig selectActiveAvatarGenerationService(@Param("serviceId") Long serviceId);

    List<AvatarGenerationServiceResponse> selectActiveAvatarGenerationServices();

    int reserveAvatarQuota(@Param("accountId") Long accountId);

    int insertFile(AssetFile file);

    int insertAvatar(@Param("id") Long id, @Param("accountId") Long accountId, @Param("name") String name,
        @Param("visibility") String visibility);

    int insertAvatarVersion(@Param("id") Long id, @Param("avatarId") Long avatarId, @Param("accountId") Long accountId,
        @Param("sourceFileId") Long sourceFileId, @Param("pipelineVersion") String pipelineVersion,
        @Param("generationRecipe") String generationRecipe);

    int insertQuotaReservation(@Param("id") Long id, @Param("accountId") Long accountId, @Param("businessId") String businessId);

    int insertQuotaEntry(@Param("id") Long id, @Param("accountId") Long accountId, @Param("reservationId") Long reservationId,
        @Param("eventKey") String eventKey);

    int insertGenerationTask(@Param("id") Long id, @Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("avatarVersionId") Long avatarVersionId, @Param("sourceFileId") Long sourceFileId,
        @Param("officialServiceId") Long officialServiceId, @Param("serviceSnapshot") String serviceSnapshot,
        @Param("pipelineVersion") String pipelineVersion, @Param("quotaReservationId") Long quotaReservationId,
        @Param("requestId") String requestId, @Param("requestHash") byte[] requestHash);

    int insertGenerationActionStep(@Param("id") Long id, @Param("accountId") Long accountId, @Param("taskId") Long taskId,
        @Param("stepKey") String stepKey, @Param("actionCode") String actionCode,
        @Param("reservedAttemptId") Long reservedAttemptId);

    int insertOutbox(@Param("id") Long id, @Param("accountId") Long accountId, @Param("eventId") String eventId,
        @Param("eventType") String eventType, @Param("aggregateType") String aggregateType,
        @Param("aggregateId") String aggregateId, @Param("traceId") String traceId, @Param("payload") String payload);
}
