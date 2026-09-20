package com.ruoyi.system.asset.dto;

/** 人工验收并发布候选 Avatar 版本。 */
public class PublishAvatarVersionRequest
{
    private Boolean visualAccepted;
    private String reviewNote;

    public Boolean getVisualAccepted() { return visualAccepted; }
    public void setVisualAccepted(Boolean value) { visualAccepted = value; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String value) { reviewNote = value; }
}
