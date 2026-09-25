package com.ruoyi.system.asset.domain;

/** p_file 的最小账户私有文件视图。 */
public class AssetFile
{
    private Long id;
    private Long accountId;
    private String purpose;
    private String storageProvider;
    private String bucket;
    private String objectKey;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private byte[] sha256;
    private Integer width;
    private Integer height;
    private String status;
    private String rightsNoticeVersion;
    private Long storageReservationId;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long value) { accountId = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public String getStorageProvider() { return storageProvider; }
    public void setStorageProvider(String value) { storageProvider = value; }
    public String getBucket() { return bucket; }
    public void setBucket(String value) { bucket = value; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String value) { objectKey = value; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String value) { originalName = value; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { contentType = value; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long value) { sizeBytes = value; }
    public byte[] getSha256() { return sha256; }
    public void setSha256(byte[] value) { sha256 = value; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer value) { width = value; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer value) { height = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getRightsNoticeVersion() { return rightsNoticeVersion; }
    public void setRightsNoticeVersion(String value) { rightsNoticeVersion = value; }
    public Long getStorageReservationId() { return storageReservationId; }
    public void setStorageReservationId(Long value) { storageReservationId = value; }
}
