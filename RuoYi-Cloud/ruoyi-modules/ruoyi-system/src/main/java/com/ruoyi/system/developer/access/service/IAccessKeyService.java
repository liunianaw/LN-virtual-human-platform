package com.ruoyi.system.developer.access.service;

import java.util.List;
import java.util.Map;

public interface IAccessKeyService
{
    record Principal(long keyId, long accountId, Long applicationId, String type, List<String> scopes, long authEpoch) {}

    Principal authenticate(String authorization, String requiredType, String requiredScope);
    List<Map<String, Object>> list(long accountId, String type, Long applicationId);
    Map<String, Object> resetApplication(long accountId, long applicationId, String name, String idempotencyKey, Principal caller);
    Map<String, Object> changeApplicationStatus(long accountId, long applicationId, long keyId, String status, String idempotencyKey, Principal caller);
}
