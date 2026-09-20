package com.ruoyi.system.asset.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** Avatar 制作页可安全展示的官方生成服务标识，不包含连接地址或任何秘密。 */
public class AvatarGenerationServiceResponse
{
    @JsonSerialize(using = ToStringSerializer.class)
    private Long serviceId;
    private String name;
    private String providerCode;
    private String modelId;
    private Long revision;

    public Long getServiceId() { return serviceId; }
    public void setServiceId(Long value) { serviceId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String value) { providerCode = value; }
    public String getModelId() { return modelId; }
    public void setModelId(String value) { modelId = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
}
