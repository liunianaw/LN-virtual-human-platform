package com.ruoyi.system.developer.session.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface BusinessSessionMapper
{
    Map<String, Object> snapshot(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    List<Map<String, Object>> skills(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    Map<String, Object> currentForUpdate(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    List<Map<String, Object>> resources(@Param("applicationId") long applicationId);
    int availableSession(@Param("accountId") long accountId, @Param("applicationId") long applicationId,
        @Param("sessionId") long sessionId);
    Long maxRecordingBytes(@Param("accountId") long accountId);
    Integer maxSessionsForUpdate(@Param("accountId") long accountId);
    int occupiedSessions(@Param("accountId") long accountId, @Param("sessionId") long sessionId);
    int availableCurrent(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
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
