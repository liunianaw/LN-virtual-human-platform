package com.ruoyi.system.developer.access.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AccessKeyMapper
{
    long nextId();
    Map<String, Object> accountForUpdate(@Param("accountId") long accountId);
    Map<String, Object> account(@Param("accountId") long accountId);
    Map<String, Object> applicationForUpdate(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    Map<String, Object> application(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    List<Map<String, Object>> list(@Param("accountId") long accountId, @Param("type") String type, @Param("applicationId") Long applicationId);
    Map<String, Object> byIdForUpdate(@Param("accountId") long accountId, @Param("id") long id);
    Map<String, Object> summary(@Param("accountId") long accountId, @Param("id") long id);
    Map<String, Object> byPublicId(@Param("publicId") String publicId);
    Map<String, Object> activeApplication(@Param("applicationId") long applicationId);
    void insert(@Param("id") long id, @Param("accountId") long accountId, @Param("applicationId") Long applicationId,
        @Param("type") String type, @Param("name") String name, @Param("publicId") String publicId,
        @Param("hash") byte[] hash, @Param("suffix") String suffix, @Param("scopes") String scopes, @Param("now") Instant now);
    int changeStatus(@Param("id") long id, @Param("status") String status);
    void touch(@Param("id") long id);
    Map<String, Object> idempotency(@Param("accountId") long accountId, @Param("scope") String scope, @Param("requestId") String requestId);
    void insertIdempotency(@Param("id") long id, @Param("accountId") long accountId, @Param("scope") String scope,
        @Param("requestId") String requestId, @Param("hash") byte[] hash, @Param("resourceId") long resourceId, @Param("expiresAt") Instant expiresAt);
    void outbox(@Param("id") long id, @Param("accountId") long accountId, @Param("eventId") String eventId,
        @Param("aggregateId") String aggregateId, @Param("payload") String payload, @Param("now") Instant now);
    void audit(@Param("id") long id, @Param("accountId") long accountId, @Param("keyId") long keyId,
        @Param("actorType") String actorType, @Param("actorKeyId") Long actorKeyId, @Param("action") String action);
}
