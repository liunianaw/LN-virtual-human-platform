package com.ruoyi.system.asset.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AvatarProductionMapper
{
    Map<String, Object> lockVersion(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    Map<String, Object> operation(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("requestId") String requestId);
    Map<String, Object> generationOperation(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("requestId") String requestId);
    Map<String, Object> generationContext(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode);
    int insertActionTask(@Param("id") Long id, @Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("versionId") Long versionId, @Param("sourceFileId") Long sourceFileId,
        @Param("officialServiceId") Long officialServiceId, @Param("serviceSnapshot") String serviceSnapshot,
        @Param("pipelineVersion") String pipelineVersion, @Param("reservationId") Long reservationId,
        @Param("requestId") String requestId);
    int saveSelection(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("resultId") Long resultId, @Param("revision") Long revision);
    int initializeSelection(@Param("accountId") Long accountId, @Param("versionId") Long versionId, @Param("actionCode") String actionCode);
    int startGeneration(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("revision") Long revision,
        @Param("attemptId") Long attemptId);
    int invalidateAssembly(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    int rememberSelection(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("requestId") String requestId,
        @Param("hash") byte[] hash, @Param("response") String response);
    int rememberGeneration(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("requestId") String requestId,
        @Param("hash") byte[] hash, @Param("response") String response);
    Map<String, Object> attemptOperation(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("operationCode") String operationCode,
        @Param("requestId") String requestId);
    Map<String, Object> attemptContext(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("attemptId") Long attemptId);
    int scheduleAttemptRecovery(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("attemptId") Long attemptId,
        @Param("expectedRevision") Long expectedRevision);
    int closeAttemptSelection(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("attemptId") Long attemptId,
        @Param("retainResultId") Long retainResultId, @Param("expectedRevision") Long expectedRevision);
    int stopAttemptRecovery(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("attemptId") Long attemptId);
    int reactivateAttempt(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("attemptId") Long attemptId);
    int reactivateTask(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("attemptId") Long attemptId);
    int rememberAttemptOperation(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("operationCode") String operationCode,
        @Param("requestId") String requestId, @Param("hash") byte[] hash, @Param("response") String response);
    List<Map<String, Object>> selectedResults(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    int countAssemblyBlockers(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    int deleteDraftActions(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    int insertFormalAction(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("versionId") Long versionId, @Param("resultId") Long resultId);
    int finishAssembly(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("revision") Long revision, @Param("baseFileId") Long baseFileId,
        @Param("manifestFileId") Long manifestFileId, @Param("previewFileId") Long previewFileId);
    Map<String, Object> assemblyOperation(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("requestId") String requestId);
    int rememberAssembly(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("requestId") String requestId, @Param("hash") byte[] hash, @Param("response") String response);
    Map<String, Object> ownedVersion(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId, @Param("versionId") Long versionId);
    List<Map<String, Object>> selections(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    List<Map<String, Object>> results(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    List<Map<String, Object>> steps(@Param("accountId") Long accountId, @Param("versionId") Long versionId);
    Map<String, Object> resultPreview(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("resultId") Long resultId);
    Map<String, Object> lockOwnedAvatar(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId);
    Map<String, Object> versionOperation(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("requestId") String requestId);
    Map<String, Object> baseVersion(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("baseVersionId") Long baseVersionId);
    int insertCandidateVersion(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("avatarId") Long avatarId, @Param("versionNo") Integer versionNo,
        @Param("sourceFileId") Long sourceFileId, @Param("pipelineVersion") String pipelineVersion,
        @Param("generationRecipe") String generationRecipe);
    int copyActionResult(@Param("id") Long id, @Param("accountId") Long accountId,
        @Param("baseVersionId") Long baseVersionId, @Param("newVersionId") Long newVersionId,
        @Param("actionCode") String actionCode);
    int initializeCopiedSelection(@Param("accountId") Long accountId, @Param("versionId") Long versionId,
        @Param("actionCode") String actionCode, @Param("resultId") Long resultId);
    int bumpAvatarRevision(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("expectedRevision") Long expectedRevision);
    int rememberVersionOperation(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("requestId") String requestId, @Param("hash") byte[] hash, @Param("response") String response);
}
