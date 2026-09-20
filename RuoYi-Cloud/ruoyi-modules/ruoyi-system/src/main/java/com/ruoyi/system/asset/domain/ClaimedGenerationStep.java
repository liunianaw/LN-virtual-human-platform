package com.ruoyi.system.asset.domain;

/** 被 Worker 领取前的 READY 动作步骤。 */
public class ClaimedGenerationStep
{
    private Long stepId;
    private Long taskId;
    private Long accountId;
    private Long avatarVersionId;
    private Long sourceFileId;
    private String sourceObjectKey;
    private String actionCode;
    private Integer attemptNo;
    private Long leaseEpoch;
    private String modelId;
    private String parameters;

    public Long getStepId() { return stepId; }
    public void setStepId(Long value) { stepId = value; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long value) { taskId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public Long getAvatarVersionId() { return avatarVersionId; }
    public void setAvatarVersionId(Long value) { avatarVersionId = value; }
    public Long getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(Long value) { sourceFileId = value; }
    public String getSourceObjectKey() { return sourceObjectKey; }
    public void setSourceObjectKey(String value) { sourceObjectKey = value; }
    public String getActionCode() { return actionCode; }
    public void setActionCode(String value) { actionCode = value; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer value) { attemptNo = value; }
    public Long getLeaseEpoch() { return leaseEpoch; }
    public void setLeaseEpoch(Long value) { leaseEpoch = value; }
    public String getModelId() { return modelId; }
    public void setModelId(String value) { modelId = value; }
    public String getParameters() { return parameters; }
    public void setParameters(String value) { parameters = value; }
}
