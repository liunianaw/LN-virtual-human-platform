package com.ruoyi.system.skill.service;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/** A Skill has one mutable current configuration protected by revision. */
public interface ISkillService
{
    record SkillInput(@NotBlank String name, String description, @NotBlank String skillType,
        String toolName, String instructions, Object contextRequirements, String toolUrl,
        String httpMethod, Object inputSchema, Object outputSchema, String accessToken,
        boolean requiresUserCredential, Object identityBinding, List<String> frontendFields,
        Integer timeoutMs, Long maxResultBytes, Integer maxCallsPerSession, String importFormat) { }
    record StatusInput(@NotBlank String status, @NotBlank String reason) { }
    record RuntimeBinding(long accountId, long applicationId, long sessionId, long skillId,
        Long toolSecretId, String toolUrl) { }
    record ResolvedSkill(long skillId, String pinnedAddress, String accessToken) { }

    Map<String, Object> list(long accountId, int pageNum, int pageSize);
    Map<String, Object> listOfficial(long accountId, int pageNum, int pageSize);
    Map<String, Object> candidates(long accountId);
    Map<String, Object> detail(long accountId, long skillId);
    Map<String, Object> detailOfficial(long accountId, long skillId);
    Map<String, Object> create(long accountId, long actorId, SkillInput input, String key, boolean official);
    Map<String, Object> update(long accountId, long actorId, long skillId, SkillInput input,
        String ifMatch, String key, boolean official);
    Map<String, Object> changeStatus(long accountId, long skillId, StatusInput input,
        String ifMatch, String key, boolean official);
    Map<String, Object> delete(long accountId, long skillId, String ifMatch, String key, boolean official);
    Map<String, Object> checkConnection(long accountId, long skillId, boolean official);
    ResolvedSkill resolve(RuntimeBinding binding);
}
