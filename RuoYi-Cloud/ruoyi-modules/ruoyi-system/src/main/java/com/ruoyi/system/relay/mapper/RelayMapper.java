package com.ruoyi.system.relay.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.officialservice.domain.OfficialSecret;

public interface RelayMapper
{
    long nextId();
    Integer lockAccount(@Param("accountId") long accountId);
    List<Map<String, Object>> page(@Param("accountId") long accountId, @Param("offset") int offset, @Param("limit") int limit);
    int count(@Param("accountId") long accountId);
    Map<String, Object> service(@Param("accountId") long accountId, @Param("relayId") long relayId);
    Map<String, Object> serviceAnyStatus(@Param("accountId") long accountId, @Param("relayId") long relayId);
    Map<String, Object> lockService(@Param("accountId") long accountId, @Param("relayId") long relayId);
    Map<String, Object> lockAnyService(@Param("relayId") long relayId);
    Map<String, Object> version(@Param("accountId") long accountId, @Param("versionId") long versionId);
    List<Map<String, Object>> versions(@Param("accountId") long accountId, @Param("relayId") long relayId);
    List<Map<String, Object>> grants(@Param("accountId") long accountId, @Param("relayId") long relayId);
    int nextVersionNo(@Param("relayId") long relayId);
    int countOwnedApplication(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    int countReferences(@Param("relayId") long relayId);
    int countRuntimeBinding(@Param("accountId") long accountId, @Param("applicationId") long applicationId,
        @Param("sessionId") long sessionId, @Param("versionId") long versionId, @Param("capability") String capability);
    Map<String, Object> idempotency(@Param("accountId") long accountId, @Param("scope") String scope,
        @Param("requestId") String requestId);
    void insertIdempotency(@Param("id") long id, @Param("accountId") long accountId,
        @Param("scope") String scope, @Param("requestId") String requestId,
        @Param("hash") byte[] hash, @Param("relayId") long relayId);
    OfficialSecret secret(@Param("accountId") long accountId, @Param("secretId") long secretId);
    void insertSecret(@Param("secret") OfficialSecret secret, @Param("accountId") long accountId,
        @Param("name") String name, @Param("suffix") String suffix);
    int updateSecret(@Param("secret") OfficialSecret secret, @Param("accountId") long accountId,
        @Param("suffix") String suffix);
    int disableSecret(@Param("accountId") long accountId, @Param("secretId") long secretId);
    void insertService(@Param("id") long id, @Param("accountId") long accountId, @Param("name") String name,
        @Param("description") String description, @Param("secretId") long secretId, @Param("grantMode") String grantMode);
    void insertVersion(@Param("id") long id, @Param("accountId") long accountId, @Param("relayId") long relayId,
        @Param("versionNo") int versionNo, @Param("baseUrl") String baseUrl, @Param("capabilities") String capabilities,
        @Param("endpoints") String endpoints, @Param("timeoutMs") int timeoutMs,
        @Param("maxResponseBytes") long maxResponseBytes, @Param("hash") byte[] hash, @Param("actorId") long actorId);
    int selectVersion(@Param("accountId") long accountId, @Param("relayId") long relayId,
        @Param("versionId") long versionId, @Param("epoch") long epoch);
    int setGrantMode(@Param("accountId") long accountId, @Param("relayId") long relayId,
        @Param("grantMode") String grantMode, @Param("epoch") long epoch);
    void revokeGrants(@Param("accountId") long accountId, @Param("relayId") long relayId);
    void upsertGrant(@Param("id") long id, @Param("accountId") long accountId, @Param("relayId") long relayId,
        @Param("applicationId") long applicationId, @Param("scopes") String scopes);
    int setStatus(@Param("accountId") long accountId, @Param("relayId") long relayId,
        @Param("status") String status, @Param("epoch") long epoch);
    int bumpEpoch(@Param("accountId") long accountId, @Param("relayId") long relayId, @Param("epoch") long epoch);
    int resetTest(@Param("accountId") long accountId, @Param("relayId") long relayId, @Param("epoch") long epoch);
    int setAdminDisabled(@Param("relayId") long relayId, @Param("disabled") boolean disabled);
    int saveTest(@Param("accountId") long accountId, @Param("relayId") long relayId,
        @Param("versionId") long versionId, @Param("epoch") long epoch,
        @Param("status") String status, @Param("error") String error);
    void insertAdminEvent(@Param("id") long id, @Param("accountId") long accountId,
        @Param("eventId") String eventId, @Param("relayId") long relayId,
        @Param("payload") String payload);
    void insertStatusEvent(@Param("id") long id, @Param("accountId") long accountId,
        @Param("eventId") String eventId, @Param("relayId") long relayId,
        @Param("payload") String payload);
}
