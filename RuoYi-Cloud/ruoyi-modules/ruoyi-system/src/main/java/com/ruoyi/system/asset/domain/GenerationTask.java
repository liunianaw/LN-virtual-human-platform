package com.ruoyi.system.asset.domain;

import java.time.LocalDateTime;

/** p_generation_task 的账户查询视图。 */
public class GenerationTask
{
    private Long id;
    private Long accountId;
    private Long avatarId;
    private Long avatarVersionId;
    private Long sourceFileId;
    private Long quotaReservationId;
    private String requestId;
    private String status;
    private String internalState;
    private Integer progress;
    private String errorCode;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public Long getAvatarId() { return avatarId; }
    public void setAvatarId(Long value) { avatarId = value; }
    public Long getAvatarVersionId() { return avatarVersionId; }
    public void setAvatarVersionId(Long value) { avatarVersionId = value; }
    public Long getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(Long value) { sourceFileId = value; }
    public Long getQuotaReservationId() { return quotaReservationId; }
    public void setQuotaReservationId(Long value) { quotaReservationId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getInternalState() { return internalState; }
    public void setInternalState(String value) { internalState = value; }
    public Integer getProgress() { return progress; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public void setProgress(Integer value) { progress = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
