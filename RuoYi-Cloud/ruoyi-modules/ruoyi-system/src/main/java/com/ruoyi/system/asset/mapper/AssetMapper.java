package com.ruoyi.system.asset.mapper;

import java.util.List;
import java.util.Map;
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
    List<GenerationTask> selectPageTasksByAccount(@Param("accountId") Long accountId,
        @Param("limit") int limit, @Param("offset") int offset);
    int countTasksByAccount(@Param("accountId") Long accountId);
    List<GenerationTask> selectConsoleTasks(@Param("accountId") Long accountId,
        @Param("visibility") String visibility, @Param("limit") int limit, @Param("offset") int offset);
    int countConsoleTasks(@Param("accountId") Long accountId, @Param("visibility") String visibility);
    List<java.util.Map<String, Object>> selectTaskStepsByAccount(@Param("accountId") Long accountId,
        @Param("taskId") Long taskId);

    GenerationServiceConfig selectActiveAvatarGenerationService(@Param("serviceId") Long serviceId);

    List<AvatarGenerationServiceResponse> selectActiveAvatarGenerationServices();

    int reserveAvatarQuota(@Param("accountId") Long accountId);
    Integer maxGenerationTasksForUpdate(@Param("accountId") Long accountId);
    int countActiveGenerationTasks(@Param("accountId") Long accountId);
    Long availableAvatarQuota(@Param("accountId") Long accountId);
    Map<String, Object> taskReservation(@Param("accountId") Long accountId, @Param("taskId") Long taskId);
    int changeTaskReservation(@Param("accountId") Long accountId, @Param("taskId") Long taskId,
        @Param("oldReservationId") Long oldReservationId, @Param("reservationId") Long reservationId,
        @Param("reservationNo") int reservationNo);
    int settleQuotaReservation(@Param("reservationId") Long reservationId);
    int releaseQuotaReservation(@Param("reservationId") Long reservationId);
    int reviewQuotaReservation(@Param("reservationId") Long reservationId);
    int settleAvatarQuota(@Param("accountId") Long accountId);
    int releaseAvatarQuota(@Param("accountId") Long accountId);
    List<Map<String, Object>> timedOutTasksForQuota();

    Long maxFileBytesForUpdate(@Param("accountId") Long accountId);
    int reserveStorageBalance(@Param("accountId") Long accountId, @Param("bytes") long bytes);
    int settleStorageBalance(@Param("accountId") Long accountId, @Param("bytes") long bytes);
    int releaseStorageBalance(@Param("accountId") Long accountId, @Param("bytes") long bytes);
    int freeStorageBalance(@Param("accountId") Long accountId, @Param("bytes") long bytes);
    int insertStorageReservation(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("fileId") Long fileId, @Param("bytes") long bytes);
    int insertStorageEntry(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("reservationId") Long reservationId, @Param("eventKey") String eventKey,
        @Param("entryType") String entryType, @Param("deltaUsed") long deltaUsed,
        @Param("deltaReserved") long deltaReserved);
    Map<String, Object> storageFileForUpdate(@Param("accountId") Long accountId, @Param("fileId") Long fileId);
    int settleStorageReservation(@Param("reservationId") Long reservationId, @Param("bytes") long bytes);
    int releaseStorageReservation(@Param("reservationId") Long reservationId);
    int completeStorageFile(@Param("accountId") Long accountId, @Param("fileId") Long fileId);
    int failStorageFile(@Param("accountId") Long accountId, @Param("fileId") Long fileId);
    int countStorageFreeEntry(@Param("accountId") Long accountId, @Param("fileId") Long fileId);
    List<Map<String, Object>> expiredStorageUploads();
    int claimExpiredStorageUpload(@Param("accountId") Long accountId, @Param("fileId") Long fileId);

    int insertFile(AssetFile file);

    int insertAvatar(@Param("id") Long id, @Param("accountId") Long accountId, @Param("name") String name,
        @Param("visibility") String visibility);

    int insertAvatarVersion(@Param("id") Long id, @Param("avatarId") Long avatarId, @Param("accountId") Long accountId,
        @Param("sourceFileId") Long sourceFileId, @Param("pipelineVersion") String pipelineVersion,
        @Param("generationRecipe") String generationRecipe);

    int insertQuotaReservation(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("taskId") Long taskId, @Param("reservationNo") int reservationNo);

    int insertQuotaEntry(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("reservationId") Long reservationId, @Param("eventKey") String eventKey,
        @Param("entryType") String entryType, @Param("deltaUsed") int deltaUsed,
        @Param("deltaReserved") int deltaReserved);

    int insertGenerationTask(@Param("id") Long id, @Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("avatarVersionId") Long avatarVersionId, @Param("sourceFileId") Long sourceFileId,
        @Param("officialServiceId") Long officialServiceId, @Param("serviceSnapshot") String serviceSnapshot,
        @Param("pipelineVersion") String pipelineVersion, @Param("quotaReservationId") Long quotaReservationId,
        @Param("requestId") String requestId, @Param("requestHash") byte[] requestHash);

    int insertCharacterCompletionStep(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("taskId") Long taskId, @Param("reservedAttemptId") Long reservedAttemptId);

    int insertGenerationActionStep(@Param("id") Long id, @Param("accountId") Long accountId, @Param("taskId") Long taskId,
        @Param("stepKey") String stepKey, @Param("actionCode") String actionCode,
        @Param("reservedAttemptId") Long reservedAttemptId);

    int insertOutbox(@Param("id") Long id, @Param("accountId") Long accountId, @Param("eventId") String eventId,
        @Param("eventType") String eventType, @Param("aggregateType") String aggregateType,
        @Param("aggregateId") String aggregateId, @Param("traceId") String traceId, @Param("payload") String payload);
    String avatarVisibility(@org.apache.ibatis.annotations.Param("accountId") Long accountId,
        @org.apache.ibatis.annotations.Param("avatarId") Long avatarId);
}
