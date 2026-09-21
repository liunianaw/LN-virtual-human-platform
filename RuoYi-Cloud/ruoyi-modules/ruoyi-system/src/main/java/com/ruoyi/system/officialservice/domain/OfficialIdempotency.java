package com.ruoyi.system.officialservice.domain;

public class OfficialIdempotency
{
    private byte[] requestHash; private Long resourceId;
    public byte[] getRequestHash() { return requestHash; } public void setRequestHash(byte[] value) { requestHash = value; }
    public Long getResourceId() { return resourceId; } public void setResourceId(Long value) { resourceId = value; }
}
