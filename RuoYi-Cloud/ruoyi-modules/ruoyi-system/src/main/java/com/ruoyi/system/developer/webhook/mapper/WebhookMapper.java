package com.ruoyi.system.developer.webhook.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.officialservice.domain.OfficialSecret;

@Mapper
public interface WebhookMapper
{
    Long nextId();
    Integer lockAccount(@Param("accountId") long accountId);
    Map<String,Object> idempotency(@Param("accountId") long accountId, @Param("scope") String scope,
        @Param("key") String key);
    void remember(@Param("id") long id, @Param("accountId") long accountId, @Param("scope") String scope,
        @Param("key") String key, @Param("hash") byte[] hash, @Param("resourceId") long resourceId);
    List<Map<String,Object>> page(@Param("accountId") long accountId, @Param("limit") int limit,
        @Param("offset") int offset);
    long count(@Param("accountId") long accountId);
    Map<String,Object> endpoint(@Param("accountId") long accountId, @Param("endpointId") long endpointId);
    Map<String,Object> lockEndpoint(@Param("accountId") long accountId, @Param("endpointId") long endpointId);
    Map<String,Object> currentEndpoint(@Param("endpointId") long endpointId);
    void insertSecret(@Param("secret") OfficialSecret secret, @Param("accountId") long accountId,
        @Param("name") String name);
    OfficialSecret secret(@Param("accountId") long accountId, @Param("secretId") long secretId);
    int disableSecret(@Param("accountId") long accountId, @Param("secretId") long secretId);
    void insertEndpoint(@Param("id") long id, @Param("accountId") long accountId,
        @Param("input") com.ruoyi.system.developer.webhook.service.IWebhookService.CreateInput input,
        @Param("secretId") long secretId, @Param("events") String events);
    int rotate(@Param("accountId") long accountId, @Param("endpointId") long endpointId,
        @Param("oldSecretId") long oldSecretId, @Param("secretId") long secretId, @Param("revision") long revision);
    int status(@Param("accountId") long accountId, @Param("endpointId") long endpointId,
        @Param("status") String status, @Param("revision") long revision);
    Map<String,Object> terminalTask(@Param("accountId") long accountId, @Param("taskId") long taskId);
    int insertTerminalEvent(@Param("accountId") long accountId, @Param("taskId") long taskId);
    int insertMissingTerminalEvents();
    Map<String,Object> claimOutbox();
    int insertDelivery(@Param("id") long id, @Param("event") Map<String,Object> event,
        @Param("payload") String payload, @Param("snapshot") String snapshot);
    int finishOutbox(@Param("id") long id);
    Map<String,Object> claimDelivery();
    int closeUnleased(@Param("id") long id, @Param("status") String status, @Param("errorCode") String errorCode);
    int leaseDelivery(@Param("id") long id, @Param("owner") String owner);
    int insertAttempt(@Param("id") long id, @Param("delivery") Map<String,Object> delivery);
    int finishAttempt(@Param("deliveryId") long deliveryId, @Param("attemptNo") int attemptNo,
        @Param("httpStatus") Integer httpStatus, @Param("errorCode") String errorCode,
        @Param("latencyMs") long latencyMs);
    int settleDelivery(@Param("id") long id, @Param("owner") String owner,
        @Param("status") String status, @Param("httpStatus") Integer httpStatus,
        @Param("errorCode") String errorCode, @Param("delaySeconds") int delaySeconds);
    int finishStaleAttempt(@Param("deliveryId") long deliveryId, @Param("attemptNo") int attemptNo);
    List<Map<String,Object>> deliveries(@Param("accountId") long accountId, @Param("endpointId") long endpointId,
        @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to,
        @Param("limit") int limit, @Param("offset") int offset);
    long deliveryCount(@Param("accountId") long accountId, @Param("endpointId") long endpointId,
        @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
    List<Map<String,Object>> attempts(@Param("accountId") long accountId, @Param("deliveryId") long deliveryId,
        @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to,
        @Param("limit") int limit, @Param("offset") int offset);
    long attemptCount(@Param("accountId") long accountId, @Param("deliveryId") long deliveryId,
        @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
    Long deliveryOwner(@Param("accountId") long accountId, @Param("deliveryId") long deliveryId);
}
