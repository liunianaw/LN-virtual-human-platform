package com.ruoyi.system.developer.session;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.developer.session.mapper.BusinessSessionMapper;

@Service
public class BusinessSessionAuthorization
{
    private final IAccessKeyService keys;
    private final BusinessSessionMapper mapper;

    public BusinessSessionAuthorization(IAccessKeyService keys, BusinessSessionMapper mapper)
    { this.keys = keys; this.mapper = mapper; }

    public Map<String, Object> authenticate(String secret, String scope, Long configId)
    {
        if (!java.util.Set.of("sessions:create", "sessions:read", "sessions:grant", "sessions:end", "sessions:revoke").contains(scope))
            throw new ServiceException("Session Scope 无效", 400);
        IAccessKeyService.Principal key = keys.authenticate("Bearer " + secret, "APPLICATION", scope);
        if (key.applicationId() == null) throw new ServiceException("应用凭证无效", 401);
        Map<String, Object> snapshot = required(key.accountId(), key.applicationId(), configId, false);
        if (configId == null && snapshot.get("configId") == null) throw new ServiceException("应用尚未发布", 409);
        Map<String, Object> result = new LinkedHashMap<>(snapshot);
        result.put("keyId", key.keyId());
        result.put("keyEpoch", key.authEpoch());
        return result;
    }

    public Map<String, Object> check(long accountId, long applicationId, long configId)
    { return required(accountId, applicationId, configId, true); }

    @Transactional
    public Map<String, Object> reserve(long accountId, long applicationId, long configId, long sessionId, String operationId)
    {
        if (sessionId <= 0 || operationId == null || operationId.length() > 64 || operationId.isBlank()) throw new ServiceException("引用请求无效", 400);
        Map<String, Object> locked = mapper.currentForUpdate(accountId, applicationId);
        if (locked == null || !"ACTIVE".equals(locked.get("status")) || number(locked.get("adminDisabled")) != 0
            || number(locked.get("currentConfigId")) != configId) throw new ServiceException("应用配置当前不可创建 Session", 409);
        Map<String, Object> snapshot = required(accountId, applicationId, configId, true);
        Integer maximum = mapper.maxSessionsForUpdate(accountId);
        if (maximum == null || mapper.occupiedSessions(accountId, sessionId) >= maximum)
            throw new ServiceException("账号同时活跃 Session 已达上限", 429);
        if (mapper.availableConfig(accountId, applicationId, configId) != 1) throw new ServiceException("配置资源当前不可用", 422);
        for (Map<String, Object> resource : mapper.resources(configId))
            mapper.reserve(accountId, sessionId, operationId, String.valueOf(resource.get("resourceType")), number(resource.get("resourceId")));
        return snapshot;
    }

    @Transactional
    public void confirm(long accountId, long sessionId, String operationId)
    {
        mapper.confirm(accountId, sessionId, operationId);
        if (mapper.confirmed(accountId, sessionId, operationId) < 3) throw new ServiceException("Session 引用尚未预留", 409);
    }

    @Transactional
    public void release(long accountId, long sessionId, String operationId)
    { mapper.release(accountId, sessionId, operationId); }

    private Map<String, Object> required(long accountId, long applicationId, Long configId, boolean requireAvailable)
    {
        Map<String, Object> row = mapper.snapshot(accountId, applicationId, configId);
        if (row == null || !"0".equals(String.valueOf(row.get("accountStatus")))
            || !"0".equals(String.valueOf(row.get("accountDeleted")))
            || !"ACTIVE".equals(row.get("applicationStatus")) || number(row.get("adminDisabled")) != 0
            || row.get("configId") == null) throw new ServiceException("应用当前不可用", 403);
        if (requireAvailable && mapper.availableConfig(accountId, applicationId, number(row.get("configId"))) != 1)
            throw new ServiceException("配置资源当前不可用", 422);
        return row;
    }

    private static long number(Object value) { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
}
