package com.ruoyi.system.asset.domain;

/** Avatar 主表的账户范围删除视图。 */
public class AvatarRecord
{
    private Long id;
    private String status;
    private Long revision;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision = value; }
}
