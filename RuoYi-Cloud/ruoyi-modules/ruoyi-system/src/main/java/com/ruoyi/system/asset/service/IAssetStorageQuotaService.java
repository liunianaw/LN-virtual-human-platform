package com.ruoyi.system.asset.service;

/** Storage accounting is shared by reference uploads and generated asset files. */
public interface IAssetStorageQuotaService
{
    long reserve(long accountId, long fileId, long bytes);
    void complete(long accountId, long fileId);
    void fail(long accountId, long fileId);
    void free(long accountId, long fileId);
}
