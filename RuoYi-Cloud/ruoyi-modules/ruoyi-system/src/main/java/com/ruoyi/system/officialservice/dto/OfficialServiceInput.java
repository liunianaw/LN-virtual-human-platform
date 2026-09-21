package com.ruoyi.system.officialservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Non-secret administrator-managed provider configuration. */
public class OfficialServiceInput
{
    @NotBlank @Size(max = 100) private String name;
    @NotBlank @Pattern(regexp = "AVATAR_GENERATION|TTS") private String capability;
    @NotBlank @Size(max = 64) private String providerCode;
    @NotBlank @Size(max = 2048) private String endpoint;
    @NotBlank @Size(max = 128) private String modelId;
    @NotNull private Map<String, Object> parameters;
    private Long secretId;
    public String getName() { return name; } public void setName(String value) { name = value; }
    public String getCapability() { return capability; } public void setCapability(String value) { capability = value; }
    public String getProviderCode() { return providerCode; } public void setProviderCode(String value) { providerCode = value; }
    public String getEndpoint() { return endpoint; } public void setEndpoint(String value) { endpoint = value; }
    public String getModelId() { return modelId; } public void setModelId(String value) { modelId = value; }
    public Map<String, Object> getParameters() { return parameters; } public void setParameters(Map<String, Object> value) { parameters = value; }
    public Long getSecretId() { return secretId; } public void setSecretId(Long value) { secretId = value; }
}
