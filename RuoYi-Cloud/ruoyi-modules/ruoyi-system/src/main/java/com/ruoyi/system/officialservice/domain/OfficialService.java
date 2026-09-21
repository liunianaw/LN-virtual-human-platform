package com.ruoyi.system.officialservice.domain;

import java.time.Instant;

/** Persistent service metadata; plaintext credentials never appear here. */
public class OfficialService
{
    private Long id; private Long accountId; private String name; private String capability; private String providerCode;
    private String endpoint; private String modelId; private Long secretId; private String parameters; private String status;
    private Long revision; private Instant createdAt; private Instant updatedAt; private Boolean credentialConfigured;
    public Long getId() { return id; } public void setId(Long value) { id = value; }
    public Long getAccountId() { return accountId; } public void setAccountId(Long value) { accountId = value; }
    public String getName() { return name; } public void setName(String value) { name = value; }
    public String getCapability() { return capability; } public void setCapability(String value) { capability = value; }
    public String getProviderCode() { return providerCode; } public void setProviderCode(String value) { providerCode = value; }
    public String getEndpoint() { return endpoint; } public void setEndpoint(String value) { endpoint = value; }
    public String getModelId() { return modelId; } public void setModelId(String value) { modelId = value; }
    public Long getSecretId() { return secretId; } public void setSecretId(Long value) { secretId = value; }
    public String getParameters() { return parameters; } public void setParameters(String value) { parameters = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public Long getRevision() { return revision; } public void setRevision(Long value) { revision = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant value) { updatedAt = value; }
    public Boolean getCredentialConfigured() { return credentialConfigured; } public void setCredentialConfigured(Boolean value) { credentialConfigured = value; }
}
