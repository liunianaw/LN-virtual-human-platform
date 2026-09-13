package com.ruoyi.system.asset.domain;

/** 任务快照允许保存的官方生成服务非秘密字段。 */
public class GenerationServiceConfig
{
    private Long id;
    private String name;
    private String providerCode;
    private String endpoint;
    private String modelId;
    private String parameters;
    private Long revision;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String value) { providerCode = value; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String value) { endpoint = value; }
    public String getModelId() { return modelId; }
    public void setModelId(String value) { modelId = value; }
    public String getParameters() { return parameters; }
    public void setParameters(String value) { parameters = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
}
