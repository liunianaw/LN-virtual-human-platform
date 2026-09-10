package com.ruoyi.system.storage;

/** 存储适配边界：调用者生成对象键并验证所有权，不接收任意 URL 作为删除依据。 */
public interface ObjectStorage
{
    void put(String key, byte[] bytes, String contentType);
    String readUrl(String key);
    void delete(String key);
}
