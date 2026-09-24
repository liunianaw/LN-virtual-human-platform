package com.ruoyi.system.application.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ApplicationMapper
{
    long nextId();
    List<Map<String, Object>> selectApplications(@Param("accountId") long accountId, @Param("status") String status,
        @Param("offset") int offset, @Param("limit") int limit);
    int countApplications(@Param("accountId") long accountId, @Param("status") String status);
    Map<String, Object> selectApplication(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    Map<String, Object> selectApplicationForUpdate(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    Map<String, Object> selectAdminApplicationForUpdate(@Param("applicationId") long applicationId);
    int updateAdminDisabled(@Param("applicationId") long applicationId, @Param("disabled") boolean disabled);
    void insertAdminOutbox(@Param("id") long id, @Param("accountId") long accountId, @Param("eventId") String eventId,
        @Param("aggregateId") String aggregateId, @Param("payload") String payload, @Param("now") Instant now);
    List<Map<String, Object>> selectConfigVersions(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    Map<String, Object> selectConfig(@Param("accountId") long accountId, @Param("applicationId") long applicationId, @Param("configVersionId") long configVersionId);
    Map<String, Object> selectIdempotencyForUpdate(@Param("accountId") long accountId, @Param("scope") String scope, @Param("requestId") String requestId);
    void insertIdempotency(@Param("id") long id, @Param("accountId") long accountId, @Param("scope") String scope,
        @Param("requestId") String requestId, @Param("hash") byte[] hash, @Param("resourceType") String resourceType,
        @Param("resourceId") long resourceId, @Param("expiresAt") Instant expiresAt);
    void insertApplication(@Param("id") long id, @Param("accountId") long accountId, @Param("name") String name,
        @Param("description") String description, @Param("now") Instant now);
    int countAvailableAvatarVersion(@Param("accountId") long accountId, @Param("versionId") long versionId);
    int countAvailableOfficialVoiceVersion(@Param("versionId") long versionId);
    int countCurrentConfigAvailable(@Param("applicationId") long applicationId);
    int nextConfigVersionNo(@Param("applicationId") long applicationId);
    void insertConfig(@Param("id") long id, @Param("accountId") long accountId, @Param("applicationId") long applicationId,
        @Param("versionNo") int versionNo, @Param("avatarVersionId") long avatarVersionId, @Param("voiceVersionId") long voiceVersionId,
        @Param("contextPolicy") String contextPolicy, @Param("runtimeLimits") String runtimeLimits, @Param("configHash") byte[] configHash,
        @Param("now") Instant now);
    int replaceCurrentConfig(@Param("applicationId") long applicationId, @Param("configVersionId") long configVersionId,
        @Param("expectedRevision") long expectedRevision);
    void releaseCurrentReferences(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    void insertCurrentReference(@Param("id") long id, @Param("accountId") long accountId, @Param("applicationId") long applicationId,
        @Param("operationId") String operationId, @Param("resourceType") String resourceType, @Param("resourceId") long resourceId,
        @Param("now") Instant now);
    int updateStatus(@Param("applicationId") long applicationId, @Param("status") String status, @Param("expectedRevision") long expectedRevision);
    void insertOutbox(@Param("id") long id, @Param("accountId") long accountId, @Param("eventId") String eventId,
        @Param("aggregateId") String aggregateId, @Param("payload") String payload, @Param("now") Instant now);
    List<Map<String, Object>> selectAvatarChoices(@Param("accountId") long accountId);
    List<Map<String, Object>> selectVoiceChoices();
}
