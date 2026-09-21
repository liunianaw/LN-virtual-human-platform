package com.ruoyi.system.officialservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class OfficialServiceCredentialRequest
{
    @NotBlank @Size(max = 4096) private String providerKey;
    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String value) { providerKey = value; }
}
