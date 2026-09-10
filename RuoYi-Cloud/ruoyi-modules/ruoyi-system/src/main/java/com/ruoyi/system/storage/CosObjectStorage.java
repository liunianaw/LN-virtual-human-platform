package com.ruoyi.system.storage;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Date;
import com.qcloud.cos.COS;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.ObjectMetadata;
import com.ruoyi.common.core.exception.ServiceException;

/** 腾讯云官方 SDK 适配器；复用若依流式上传结构，按对象键操作私有桶。 */
public class CosObjectStorage implements ObjectStorage
{
    private final COS client;
    private final CosStorageProperties config;

    public CosObjectStorage(COS client, CosStorageProperties config)
    {
        this.client = client;
        this.config = config;
    }

    public void put(String key, byte[] bytes, String contentType)
    {
        var metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        try (var input = new ByteArrayInputStream(bytes))
        {
            client.putObject(config.getBucket(), key, input, metadata);
        }
        catch (Exception e) { throw new ServiceException("COS 上传失败，请检查存储配置和服务状态"); }
    }

    public String readUrl(String key)
    {
        try
        {
            return client.generatePresignedUrl(config.getBucket(), key,
                Date.from(Instant.now().plusSeconds(config.getReadUrlSeconds())), HttpMethodName.GET).toString();
        }
        catch (RuntimeException e) { throw new ServiceException("COS 访问链接生成失败"); }
    }

    public void delete(String key)
    {
        try { client.deleteObject(config.getBucket(), key); }
        catch (RuntimeException e) { throw new ServiceException("COS 删除失败"); }
    }
}
