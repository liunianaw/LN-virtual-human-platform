package com.ruoyi.system.developer.session.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface BusinessSessionMapper
{
    Map<String, Object> snapshot(@Param("accountId") long accountId, @Param("applicationId") long applicationId,
        @Param("configId") Long configId);
    Map<String, Object> currentForUpdate(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    List<Map<String, Object>> resources(@Param("configId") long configId);
    Integer maxSessionsForUpdate(@Param("accountId") long accountId);
    int occupiedSessions(@Param("accountId") long accountId, @Param("sessionId") long sessionId);
    int availableConfig(@Param("accountId") long accountId, @Param("applicationId") long applicationId, @Param("configId") long configId);
    void reserve(@Param("accountId") long accountId, @Param("sessionId") long sessionId,
        @Param("operationId") String operationId, @Param("resourceType") String resourceType,
        @Param("resourceId") long resourceId);
    int confirm(@Param("accountId") long accountId, @Param("sessionId") long sessionId,
        @Param("operationId") String operationId);
    int release(@Param("accountId") long accountId, @Param("sessionId") long sessionId,
        @Param("operationId") String operationId);
    int confirmed(@Param("accountId") long accountId, @Param("sessionId") long sessionId,
        @Param("operationId") String operationId);
}
