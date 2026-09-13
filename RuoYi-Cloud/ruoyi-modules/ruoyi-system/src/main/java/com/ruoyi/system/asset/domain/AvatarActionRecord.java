package com.ruoyi.system.asset.domain;

import java.math.BigDecimal;

/** 候选版本单动作及其文件引用。 */
public class AvatarActionRecord
{
    private String actionCode;
    private Long atlasFileId;
    private Long previewFileId;
    private Integer frameCount;
    private BigDecimal fps;
    private Boolean loopEnabled;
    private String frameLayout;

    public String getActionCode() { return actionCode; }
    public void setActionCode(String value) { actionCode = value; }
    public Long getAtlasFileId() { return atlasFileId; }
    public void setAtlasFileId(Long value) { atlasFileId = value; }
    public Long getPreviewFileId() { return previewFileId; }
    public void setPreviewFileId(Long value) { previewFileId = value; }
    public Integer getFrameCount() { return frameCount; }
    public void setFrameCount(Integer value) { frameCount = value; }
    public BigDecimal getFps() { return fps; }
    public void setFps(BigDecimal value) { fps = value; }
    public Boolean getLoopEnabled() { return loopEnabled; }
    public void setLoopEnabled(Boolean value) { loopEnabled = value; }
    public String getFrameLayout() { return frameLayout; }
    public void setFrameLayout(String value) { frameLayout = value; }
}
