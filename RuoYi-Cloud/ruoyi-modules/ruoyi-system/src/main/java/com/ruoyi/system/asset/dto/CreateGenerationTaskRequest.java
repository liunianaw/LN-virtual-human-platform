package com.ruoyi.system.asset.dto;

/** 新建一次完整 Avatar 制作任务的最小请求。 */
public class CreateGenerationTaskRequest
{
    private Long sourceFileId;
    private Long officialServiceId;
    private String requestId;
    private String name;

    public Long getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(Long value) { sourceFileId = value; }
    public Long getOfficialServiceId() { return officialServiceId; }
    public void setOfficialServiceId(Long value) { officialServiceId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
}
