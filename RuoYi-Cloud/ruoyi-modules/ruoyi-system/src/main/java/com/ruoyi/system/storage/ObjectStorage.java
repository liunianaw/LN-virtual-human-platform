package com.ruoyi.system.storage;

/** 存储适配边界：调用者生成对象键并验证所有权，不接收任意 URL 作为删除依据。 */
public interface ObjectStorage
{
    void put(String key, byte[] bytes, String contentType);
    String readUrl(String key);
    void delete(String key);

    /** 持久化文件账本使用的存储提供方标识。 */
    default String provider()
    {
        return "object-storage";
    }

    /** 持久化文件账本使用的桶标识；调用方绝不接收客户端提供的桶名。 */
    default String bucket()
    {
        return "";
    }
}
