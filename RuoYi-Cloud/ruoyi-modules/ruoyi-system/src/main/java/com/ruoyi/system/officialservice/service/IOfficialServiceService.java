package com.ruoyi.system.officialservice.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.officialservice.dto.OfficialServiceCredentialRequest;
import com.ruoyi.system.officialservice.dto.OfficialServiceInput;
import com.ruoyi.system.officialservice.dto.OfficialServiceStatusRequest;

public interface IOfficialServiceService
{
    Page list(String capability, String status, Integer pageNum, Integer pageSize);
    View read(long serviceId);
    View create(long administratorId, OfficialServiceInput input, String idempotencyKey);
    View update(long administratorId, long serviceId, OfficialServiceInput input, String ifMatch, String idempotencyKey);
    View replaceCredential(long administratorId, long serviceId, OfficialServiceCredentialRequest request, String ifMatch, String idempotencyKey);
    Check check(long serviceId, String ifMatch, String idempotencyKey);
    View changeStatus(long serviceId, OfficialServiceStatusRequest request, String ifMatch, String idempotencyKey);
    ResolvedService resolve(long serviceId, long expectedRevision, String purpose, Long taskId, Long voiceVersionId);
    record Page(List<View> items, int total, int pageNum, int pageSize) { }
    record View(String serviceId, String name, String capability, String providerCode, String endpoint, String modelId,
                Map<String, Object> parameters, String status, String revision, boolean credentialConfigured, String secretId) { }
    record Check(String checkedRevision, boolean configurationValid, boolean providerVerified, List<String> issues) { }
    /** Internal-only material; do not log, serialize to a public DTO, or cache beyond one provider request. */
    record ResolvedService(String providerCode, String endpoint, String modelId, Map<String, Object> parameters, String credential) { }
}
