package com.ruoyi.system.relay.service;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Account-owned LLM/ASR Relay configuration and current runtime authorization. */
public interface IRelayService
{
    record VersionInput(@NotBlank String baseUrl, @NotBlank String protocolVersion,
        @NotNull Map<String, Boolean> capabilities, Map<String, String> endpoints,
        @NotNull Integer timeoutMs, @NotNull Long maxResponseBytes) { }
    record CreateInput(@NotBlank String name, String description, @NotBlank String accessToken,
        @NotBlank String grantMode, @Valid @NotNull VersionInput version) { }
    record GrantInput(@Positive long applicationId, @NotEmpty List<String> scopes) { }
    record GrantsInput(@NotBlank String grantMode, @Valid @NotNull List<GrantInput> grants) { }
    record StatusInput(@NotBlank String status, @NotBlank String reason) { }
    record TokenInput(@NotBlank String accessToken) { }
    record RuntimeBinding(long accountId, long applicationId, long sessionId, long relayVersionId,
        String capability, String externalUserId, Long turnId) { }
    record ResolvedRelay(String baseUrl, String pinnedAddress, String protocolVersion, Map<String, Boolean> capabilities,
        Map<String, String> endpoints, int timeoutMs, long maxResponseBytes, String accessToken,
        long authEpoch, String externalUserId, long applicationId, long sessionId, Long turnId) { }

    Map<String, Object> list(long accountId, int pageNum, int pageSize);
    Map<String, Object> detail(long accountId, long relayId);
    Map<String, Object> create(long accountId, long actorId, CreateInput input, String key);
    Map<String, Object> addVersion(long accountId, long actorId, long relayId, VersionInput input, String ifMatch, String key);
    Map<String, Object> replaceGrants(long accountId, long relayId, GrantsInput input, String ifMatch, String key);
    Map<String, Object> rotateToken(long accountId, long relayId, TokenInput input, String ifMatch, String key);
    Map<String, Object> changeStatus(long accountId, long relayId, StatusInput input, String ifMatch, String key);
    Map<String, Object> delete(long accountId, long relayId, String ifMatch, String key);
    Map<String, Object> testConnection(long accountId, long relayId, String key);
    Map<String, Object> changeAdminDisabled(long administratorId, long relayId, boolean disabled, String reason);
    ResolvedRelay resolve(RuntimeBinding binding);
}
