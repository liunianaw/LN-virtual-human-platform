package com.ruoyi.system.skill.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Account-visible Prompt and HTTP Tool definitions. Versions are immutable. */
public interface ISkillService
{
    record VersionInput(@NotBlank String skillType, String toolName, String instructions,
        Object contextRequirements, String toolUrl, String httpMethod, Object inputSchema,
        Object outputSchema, String accessToken, boolean requiresUserCredential,
        Object identityBinding, List<String> frontendFields, Integer timeoutMs,
        Long maxResultBytes, Integer maxCallsPerTurn, String importFormat) { }
    record CreateInput(@NotBlank String name, String description,
        @Valid @NotNull VersionInput version) { }
    record StatusInput(@NotBlank String status, @NotBlank String reason) { }
    record RuntimeBinding(long accountId, long applicationId, long sessionId,
        long skillVersionId) { }
    record ResolvedSkill(String skillType, String instructions, Object contextRequirements,
        String toolName, String toolUrl, String pinnedAddress, String httpMethod,
        Object inputSchema, Object outputSchema, List<String> frontendFields,
        int timeoutMs, long maxResultBytes, int maxCallsPerTurn, String accessToken,
        boolean requiresUserCredential, Object identityBinding) { }

    Map<String, Object> list(long accountId, int pageNum, int pageSize);
    Map<String, Object> candidates(long accountId);
    Map<String, Object> detail(long accountId, long skillId);
    Map<String, Object> create(long accountId, long actorId, CreateInput input, String key, boolean official);
    Map<String, Object> addVersion(long accountId, long actorId, long skillId,
        VersionInput input, String ifMatch, String key);
    Map<String, Object> changeStatus(long accountId, long skillId,
        StatusInput input, String ifMatch, String key);
    Map<String, Object> delete(long accountId, long skillId, String ifMatch, String key);
    Map<String, Object> checkConnection(long accountId, long skillId);
    ResolvedSkill resolve(RuntimeBinding binding);
}
