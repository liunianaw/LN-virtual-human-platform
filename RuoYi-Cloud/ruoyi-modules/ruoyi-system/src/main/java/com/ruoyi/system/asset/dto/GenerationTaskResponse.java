package com.ruoyi.system.asset.dto;

import java.time.LocalDateTime;

/** 用户可查询的一次制作任务状态。 */
public class GenerationTaskResponse
{
    private Long taskId;
    private Long avatarId;
    private Long avatarVersionId;
    private Long sourceFileId;
    private String requestId;
    private String status;
    private String internalState;
    private Integer progress;
    private LocalDateTime createdAt;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    public Long getAvatarId() { return avatarId; }
    public void setAvatarId(Long value) { avatarId = value; }
    public Long getAvatarVersionId() { return avatarVersionId; }
    public void setAvatarVersionId(Long value) { avatarVersionId = value; }
    public Long getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(Long value) { sourceFileId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getInternalState() { return internalState; }
    public void setInternalState(String value) { internalState = value; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer value) { progress = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
