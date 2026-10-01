package com.ruoyi.system.developer.session;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.developer.session.mapper.BusinessSessionMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds one immutable Session snapshot from the current Application and Skills. */
@Service
public class BusinessSessionAuthorization
{
    private final IAccessKeyService keys;
    private final BusinessSessionMapper mapper;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public BusinessSessionAuthorization(IAccessKeyService keys, BusinessSessionMapper mapper,
        com.fasterxml.jackson.databind.ObjectMapper json)
    { this.keys = keys; this.mapper = mapper; this.json = json; }

    public Map<String, Object> authenticate(String secret, String scope)
    {
        if (!java.util.Set.of("sessions:create", "sessions:read", "sessions:grant", "sessions:end", "sessions:revoke", "skills:invoke").contains(scope))
            throw new ServiceException("Session Scope 无效", 400);
        IAccessKeyService.Principal key = keys.authenticate("Bearer " + secret, "APPLICATION", scope);
        if (key.applicationId() == null) throw new ServiceException("应用凭证无效", 401);
        Map<String, Object> result = current(key.accountId(), key.applicationId(), "sessions:create".equals(scope));
        result.put("keyId", key.keyId());
        result.put("keyEpoch", key.authEpoch());
        return result;
    }

    public Map<String, Object> check(long accountId, long applicationId)
    { return current(accountId, applicationId, false); }

    public Map<String, Object> checkSession(long accountId, long applicationId, long sessionId)
    {
        Map<String, Object> result = current(accountId, applicationId, false);
        if (sessionId > 0 && mapper.availableSession(accountId, applicationId, sessionId) != 1)
            throw new ServiceException("Session 快照资源已停用或引用无效", 403);
        return result;
    }

    @Transactional
    public Map<String, Object> reserve(long accountId, long applicationId, long applicationRevision,
        long sessionId, String operationId)
    {
        if (sessionId <= 0 || applicationRevision <= 0 || operationId == null
            || operationId.isBlank() || operationId.length() > 64) throw new ServiceException("引用请求无效", 400);
        Map<String, Object> locked = mapper.currentForUpdate(accountId, applicationId);
        if (locked == null || !"ACTIVE".equals(locked.get("status")) || number(locked.get("adminDisabled")) != 0
            || number(locked.get("applicationRevision")) != applicationRevision)
            throw new ServiceException("Application 已变化，需重新创建 Session", 409);
        Map<String, Object> snapshot = current(accountId, applicationId, true);
        Integer maximum = mapper.maxSessionsForUpdate(accountId);
        if (maximum == null || mapper.occupiedSessions(accountId, sessionId) >= maximum)
            throw new ServiceException("账号同时活跃 Session 已达上限", 429);
        for (Map<String, Object> resource : mapper.resources(applicationId))
            mapper.reserve(accountId, sessionId, operationId, String.valueOf(resource.get("resourceType")),
                number(resource.get("resourceId")));
        return snapshot;
    }

    @Transactional public void confirm(long accountId, long sessionId, String operationId)
    {
        mapper.confirm(accountId, sessionId, operationId);
        if (mapper.confirmed(accountId, sessionId, operationId) < 3)
            throw new ServiceException("Session 引用尚未完整预留", 409);
    }
    @Transactional public void release(long accountId, long sessionId, String operationId)
    { mapper.release(accountId, sessionId, operationId); }

    private Map<String, Object> current(long accountId, long applicationId, boolean includeSnapshot)
    {
        Map<String, Object> row = mapper.snapshot(accountId, applicationId);
        if (row == null || !"0".equals(String.valueOf(row.get("accountStatus")))
            || !"0".equals(String.valueOf(row.get("accountDeleted")))
            || !"ACTIVE".equals(row.get("applicationStatus")) || number(row.get("adminDisabled")) != 0)
            throw new ServiceException("Application 当前不可用", 403);
        if (includeSnapshot && mapper.availableCurrent(accountId, applicationId) != 1)
            throw new ServiceException("Application 资源当前不可用", 422);
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("allowedScopes", List.of("session:read", "avatar:read", "speak:write",
            "asr:write", "context:capture", "guidance:receive"));
        if (includeSnapshot)
        {
            List<Map<String, Object>> internal = mapper.skills(accountId, applicationId);
            if (internal.stream().anyMatch(skill -> !java.util.Set.of("PUBLISHED", "UNLISTED").contains(skill.get("status"))))
                throw new ServiceException("Application Skill 当前不可用", 422);
            internal.forEach(this::normalizeSkill);
            result.put("internalSkills", internal);
            result.put("developerConfig", Map.of(
                "revision", Long.toString(number(row.get("applicationRevision"))),
                "systemPrompt", row.get("systemPrompt") == null ? "" : row.get("systemPrompt"),
                "skills", internal.stream().map(this::safeSkill).toList()));
        }
        return result;
    }

    private void normalizeSkill(Map<String, Object> skill)
    {
        for (String key : List.of("contextRequirements", "inputSchema", "outputSchema", "identityBinding", "frontendFields"))
        {
            if (skill.get(key) instanceof String value)
            {
                try { skill.put(key, json.readTree(value)); }
                catch (Exception error) { throw new ServiceException("Skill 配置 JSON 无效", 422); }
            }
        }
        Object required = skill.get("requiresUserCredential");
        skill.put("requiresUserCredential", Boolean.TRUE.equals(required)
            || required instanceof Number number && number.intValue() != 0);
    }

    public long recordingLimit(long accountId, long applicationId)
    {
        current(accountId, applicationId, false);
        Long limit = mapper.maxRecordingBytes(accountId);
        if (limit == null || limit <= 0) throw new ServiceException("账号录音上限尚未配置", 429);
        return Math.min(1_900_000L, limit);
    }

    private Map<String, Object> safeSkill(Map<String, Object> skill)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("skillId", "name", "description", "skillType", "toolName",
            "instructions", "inputSchema", "outputSchema", "frontendFields", "maxCallsPerSession"))
            if (skill.get(key) != null) out.put(key, skill.get(key));
        if (out.get("skillId") instanceof Number value) out.put("skillId", Long.toString(value.longValue()));
        return out;
    }
    private static long number(Object value)
    { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
}
