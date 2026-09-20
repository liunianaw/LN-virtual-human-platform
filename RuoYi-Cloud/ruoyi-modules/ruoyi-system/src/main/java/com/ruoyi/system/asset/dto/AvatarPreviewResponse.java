package com.ruoyi.system.asset.dto;

import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 已授权所有者的候选或已发布版本预览。 */
public class AvatarPreviewResponse
{
    @JsonSerialize(using = ToStringSerializer.class)
    private Long avatarId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long versionId;
    private String status;
    private Integer frameWidth;
    private Integer frameHeight;
    private BigDecimal anchorX;
    private BigDecimal anchorY;
    private String baseImageUrl;
    private String manifestUrl;
    private String previewUrl;
    private String expiresAt;
    private List<AvatarActionPreviewResponse> actions;

    public Long getAvatarId() { return avatarId; }
    public void setAvatarId(Long value) { avatarId = value; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long value) { versionId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getFrameWidth() { return frameWidth; }
    public void setFrameWidth(Integer value) { frameWidth = value; }
    public Integer getFrameHeight() { return frameHeight; }
    public void setFrameHeight(Integer value) { frameHeight = value; }
    public BigDecimal getAnchorX() { return anchorX; }
    public void setAnchorX(BigDecimal value) { anchorX = value; }
    public BigDecimal getAnchorY() { return anchorY; }
    public void setAnchorY(BigDecimal value) { anchorY = value; }
    public String getBaseImageUrl() { return baseImageUrl; }
    public void setBaseImageUrl(String value) { baseImageUrl = value; }
    public String getManifestUrl() { return manifestUrl; }
    public void setManifestUrl(String value) { manifestUrl = value; }
    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String value) { previewUrl = value; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String value) { expiresAt = value; }
    public List<AvatarActionPreviewResponse> getActions() { return actions; }
    public void setActions(List<AvatarActionPreviewResponse> value) { actions = value; }
}
