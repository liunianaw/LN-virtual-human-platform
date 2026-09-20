package com.ruoyi.system.asset.dto;

import java.math.BigDecimal;

/** 给已授权所有者的单动作短时预览信息。 */
public class AvatarActionPreviewResponse
{
    private String actionCode;
    private Integer frameCount;
    private BigDecimal fps;
    private Boolean loopEnabled;
    private String frameLayout;
    private String atlasUrl;
    private String previewUrl;

    public String getActionCode() { return actionCode; }
    public void setActionCode(String value) { actionCode = value; }
    public Integer getFrameCount() { return frameCount; }
    public void setFrameCount(Integer value) { frameCount = value; }
    public BigDecimal getFps() { return fps; }
    public void setFps(BigDecimal value) { fps = value; }
    public Boolean getLoopEnabled() { return loopEnabled; }
    public void setLoopEnabled(Boolean value) { loopEnabled = value; }
    public String getFrameLayout() { return frameLayout; }
    public void setFrameLayout(String value) { frameLayout = value; }
    public String getAtlasUrl() { return atlasUrl; }
    public void setAtlasUrl(String value) { atlasUrl = value; }
    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String value) { previewUrl = value; }
}
