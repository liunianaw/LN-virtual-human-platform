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
    List<Map<String, Object>> searchApplications(@Param("accountId") long accountId, @Param("status") String status,
        @Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);
    int countSearchApplications(@Param("accountId") long accountId, @Param("status") String status, @Param("keyword") String keyword);
    Map<String, Object> selectApplication(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    Map<String, Object> selectApplicationForUpdate(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    List<Map<String, Object>> selectApplicationSkills(@Param("applicationId") long applicationId);
    Map<String, Object> selectIdempotencyForUpdate(@Param("accountId") long accountId, @Param("scope") String scope,
        @Param("requestId") String requestId);
    void insertIdempotency(@Param("id") long id, @Param("accountId") long accountId, @Param("scope") String scope,
        @Param("requestId") String requestId, @Param("hash") byte[] hash, @Param("resourceId") long resourceId,
        @Param("expiresAt") Instant expiresAt);
    void insertApplication(@Param("id") long id, @Param("accountId") long accountId, @Param("name") String name,
        @Param("description") String description, @Param("now") Instant now);
    int countAvailableAvatar(@Param("accountId") long accountId, @Param("avatarId") long avatarId);
    int countAvailableVoice(@Param("voiceId") long voiceId);
    Map<String, Object> selectSkillBindingForUpdate(@Param("accountId") long accountId, @Param("skillId") long skillId);
    List<Map<String, Object>> selectSkillChoices(@Param("accountId") long accountId);
    int updateCurrent(@Param("applicationId") long applicationId, @Param("name") String name,
        @Param("description") String description, @Param("avatarId") long avatarId, @Param("voiceId") long voiceId,
        @Param("systemPrompt") String systemPrompt, @Param("expectedRevision") long expectedRevision);
    void deleteApplicationSkills(@Param("applicationId") long applicationId);
    void insertApplicationSkill(@Param("id") long id, @Param("accountId") long accountId,
        @Param("applicationId") long applicationId, @Param("skillId") long skillId,
        @Param("sortOrder") int sortOrder, @Param("now") Instant now);
    int countCurrentConfigAvailable(@Param("applicationId") long applicationId);
    Long selectAvatarVersion(@Param("accountId") long accountId, @Param("avatarId") long avatarId);
    Long selectVoiceVersion(@Param("voiceId") long voiceId);
    Long selectVoiceFallback(@Param("version") long version);
    void releaseCurrentReferences(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    void insertCurrentReference(@Param("id") long id, @Param("accountId") long accountId,
        @Param("applicationId") long applicationId, @Param("operationId") String operationId,
        @Param("resourceType") String resourceType, @Param("resourceId") long resourceId, @Param("now") Instant now);
    int updateStatus(@Param("applicationId") long applicationId, @Param("status") String status,
        @Param("expectedRevision") long expectedRevision);
    void insertOutbox(@Param("id") long id, @Param("accountId") long accountId, @Param("eventId") String eventId,
        @Param("aggregateId") String aggregateId, @Param("payload") String payload, @Param("now") Instant now);
    List<Map<String, Object>> selectAvatarChoices(@Param("accountId") long accountId);
    List<Map<String, Object>> selectVoiceChoices();
}
