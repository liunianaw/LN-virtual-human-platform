package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 新建一次完整 Avatar 制作任务的最小请求。 */
public class CreateGenerationTaskRequest
{
    @NotNull @Positive
    private Long sourceFileId;
    @NotNull @Positive
    private Long officialServiceId;
    @NotNull @Positive
    private Long expectedServiceRevision;
    @NotBlank @RequestId
    private String requestId;
    @NotBlank @Size(max = 100)
    private String name;

    public Long getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(Long value) { sourceFileId = value; }
    public Long getOfficialServiceId() { return officialServiceId; }
    public void setOfficialServiceId(Long value) { officialServiceId = value; }
    public Long getExpectedServiceRevision() { return expectedServiceRevision; }
    public void setExpectedServiceRevision(Long value) { expectedServiceRevision = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
}
