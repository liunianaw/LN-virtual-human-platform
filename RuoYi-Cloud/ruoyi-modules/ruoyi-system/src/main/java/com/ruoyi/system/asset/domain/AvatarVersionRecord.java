package com.ruoyi.system.asset.domain;

import java.math.BigDecimal;

/** 发布校验所需的 Avatar 与候选版本字段。 */
public class AvatarVersionRecord
{
    private Long id;
    private Long avatarId;
    private Long accountId;
    private String avatarStatus;
    private String versionStatus;
    private Long baseFileId;
    private Long manifestFileId;
    private Long previewFileId;
    private Integer frameWidth;
    private Integer frameHeight;
    private BigDecimal anchorX;
    private BigDecimal anchorY;
    private String qaReport;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getAvatarId() { return avatarId; }
    public void setAvatarId(Long value) { avatarId = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getAvatarStatus() { return avatarStatus; }
    public void setAvatarStatus(String value) { avatarStatus = value; }
    public String getVersionStatus() { return versionStatus; }
    public void setVersionStatus(String value) { versionStatus = value; }
    public Long getBaseFileId() { return baseFileId; }
    public void setBaseFileId(Long value) { baseFileId = value; }
    public Long getManifestFileId() { return manifestFileId; }
    public void setManifestFileId(Long value) { manifestFileId = value; }
    public Long getPreviewFileId() { return previewFileId; }
    public void setPreviewFileId(Long value) { previewFileId = value; }
    public Integer getFrameWidth() { return frameWidth; }
    public void setFrameWidth(Integer value) { frameWidth = value; }
    public Integer getFrameHeight() { return frameHeight; }
    public void setFrameHeight(Integer value) { frameHeight = value; }
    public BigDecimal getAnchorX() { return anchorX; }
    public void setAnchorX(BigDecimal value) { anchorX = value; }
    public BigDecimal getAnchorY() { return anchorY; }
    public void setAnchorY(BigDecimal value) { anchorY = value; }
    public String getQaReport() { return qaReport; }
    public void setQaReport(String value) { qaReport = value; }
}
