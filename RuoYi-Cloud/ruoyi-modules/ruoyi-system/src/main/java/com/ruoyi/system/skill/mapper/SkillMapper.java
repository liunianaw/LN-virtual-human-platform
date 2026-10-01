package com.ruoyi.system.skill.mapper;

import com.ruoyi.system.officialservice.domain.OfficialSecret;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface SkillMapper
{
    long nextId();
    Integer lockAccount(@Param("accountId") long accountId);
    List<Map<String, Object>> page(@Param("accountId") long accountId, @Param("offset") int offset, @Param("limit") int limit);
    int count(@Param("accountId") long accountId);
    List<Map<String, Object>> pageOfficial(@Param("accountId") long accountId, @Param("offset") int offset, @Param("limit") int limit);
    int countOfficial(@Param("accountId") long accountId);
    List<Map<String, Object>> candidates(@Param("accountId") long accountId);
    Map<String, Object> skill(@Param("accountId") long accountId, @Param("skillId") long skillId);
    Map<String, Object> officialSkill(@Param("accountId") long accountId, @Param("skillId") long skillId);
    Map<String, Object> anySkill(@Param("accountId") long accountId, @Param("skillId") long skillId);
    Map<String, Object> lockSkill(@Param("accountId") long accountId, @Param("skillId") long skillId,
        @Param("visibility") String visibility);
    int toolNameCount(@Param("accountId") long accountId, @Param("skillId") long skillId,
        @Param("toolName") String toolName);
    int referenceCount(@Param("skillId") long skillId);
    int runtimeBindingCount(@Param("accountId") long accountId, @Param("applicationId") long applicationId,
        @Param("sessionId") long sessionId, @Param("skillId") long skillId);
    Map<String, Object> idempotency(@Param("accountId") long accountId, @Param("scope") String scope,
        @Param("requestId") String requestId);
    void insertIdempotency(@Param("id") long id, @Param("accountId") long accountId,
        @Param("scope") String scope, @Param("requestId") String requestId,
        @Param("hash") byte[] hash, @Param("skillId") long skillId);
    void insertSkill(@Param("id") long id, @Param("accountId") long accountId,
        @Param("visibility") String visibility, @Param("name") String name,
        @Param("description") String description, @Param("skillType") String skillType,
        @Param("toolName") String toolName, @Param("instructions") String instructions,
        @Param("context") String context, @Param("toolUrl") String toolUrl,
        @Param("httpMethod") String httpMethod, @Param("inputSchema") String inputSchema,
        @Param("outputSchema") String outputSchema, @Param("secretId") Long secretId,
        @Param("userCredential") boolean userCredential, @Param("identityBinding") String identityBinding,
        @Param("frontendFields") String frontendFields, @Param("timeoutMs") Integer timeoutMs,
        @Param("maxResultBytes") Long maxResultBytes, @Param("maxCallsPerSession") Integer maxCallsPerSession,
        @Param("importFormat") String importFormat, @Param("hash") byte[] hash);
    int updateSkill(@Param("accountId") long accountId, @Param("skillId") long skillId,
        @Param("visibility") String visibility, @Param("revision") long revision,
        @Param("name") String name, @Param("description") String description,
        @Param("skillType") String skillType, @Param("toolName") String toolName,
        @Param("instructions") String instructions, @Param("context") String context,
        @Param("toolUrl") String toolUrl, @Param("httpMethod") String httpMethod,
        @Param("inputSchema") String inputSchema, @Param("outputSchema") String outputSchema,
        @Param("secretId") Long secretId, @Param("userCredential") boolean userCredential,
        @Param("identityBinding") String identityBinding, @Param("frontendFields") String frontendFields,
        @Param("timeoutMs") Integer timeoutMs, @Param("maxResultBytes") Long maxResultBytes,
        @Param("maxCallsPerSession") Integer maxCallsPerSession, @Param("importFormat") String importFormat,
        @Param("hash") byte[] hash);
    int setStatus(@Param("accountId") long accountId, @Param("skillId") long skillId,
        @Param("visibility") String visibility, @Param("status") String status, @Param("revision") long revision);
    void insertSecret(@Param("secret") OfficialSecret secret, @Param("accountId") long accountId,
        @Param("name") String name, @Param("suffix") String suffix);
    OfficialSecret secret(@Param("accountId") long accountId, @Param("secretId") long secretId);
    void disableSecret(@Param("secretId") long secretId);
    void disableSecrets(@Param("skillId") long skillId);
}
