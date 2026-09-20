package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 人工验收并发布候选 Avatar 版本。 */
public class PublishAvatarVersionRequest
{
    @NotNull
    private Boolean visualAccepted;
    @NotBlank @Size(max = 500)
    private String reviewNote;

    public Boolean getVisualAccepted() { return visualAccepted; }
    public void setVisualAccepted(Boolean value) { visualAccepted = value; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String value) { reviewNote = value; }
}
