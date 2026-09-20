package com.ruoyi.system.asset.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 私有参考图读取响应；签名 URL 不进入 p_file。 */
public class AssetFileResponse
{
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;
    private String contentType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private String readUrl;

    public Long getFileId() { return fileId; }
    public void setFileId(Long value) { fileId = value; }
    public String getContentType() { return contentType; }
    public void setContentType(String value) { contentType = value; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long value) { sizeBytes = value; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer value) { width = value; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer value) { height = value; }
    public String getReadUrl() { return readUrl; }
    public void setReadUrl(String value) { readUrl = value; }
}
