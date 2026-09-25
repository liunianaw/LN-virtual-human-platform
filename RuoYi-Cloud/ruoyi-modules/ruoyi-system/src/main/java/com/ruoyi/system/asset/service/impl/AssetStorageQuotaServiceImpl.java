package com.ruoyi.system.asset.service.impl;

import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.IAssetStorageQuotaService;

/** Every method runs in its caller's database transaction. */
@Service
public class AssetStorageQuotaServiceImpl implements IAssetStorageQuotaService
{
    private final AssetMapper mapper;

    public AssetStorageQuotaServiceImpl(AssetMapper mapper) { this.mapper = mapper; }

    @Override
    public long reserve(long accountId, long fileId, long bytes)
    {
        Long maximum = mapper.maxFileBytesForUpdate(accountId);
        if (bytes < 0 || maximum == null || bytes > maximum)
            throw new ServiceException("文件超过账号单文件上限或账号上限未配置", 429);
        if (mapper.reserveStorageBalance(accountId, bytes) != 1)
            throw new ServiceException("账号存储额度不足或未配置", 429);
        long reservationId = nextId();
        mapper.insertStorageReservation(reservationId, accountId, fileId, bytes);
        entry(accountId, fileId, reservationId, "reserve", "RESERVE", 0, bytes);
        return reservationId;
    }

    @Override
    public void complete(long accountId, long fileId)
    {
        Map<String, Object> row = requireFile(accountId, fileId);
        if ("SETTLED".equals(row.get("state"))) return;
        if (!"RESERVED".equals(row.get("state")) || !"UPLOADING".equals(row.get("status"))
            && !"AVAILABLE".equals(row.get("status"))) throw new ServiceException("文件存储预占状态已变化", 409);
        long bytes = number(row, "sizeBytes"), reservationId = number(row, "reservationId");
        if (mapper.settleStorageReservation(reservationId, bytes) != 1
            || mapper.settleStorageBalance(accountId, bytes) != 1
            || "UPLOADING".equals(row.get("status")) && mapper.completeStorageFile(accountId, fileId) != 1)
            throw new ServiceException("文件存储额度结算失败", 409);
        entry(accountId, fileId, reservationId, "settle", "SETTLE", bytes, -bytes);
    }

    @Override
    public void fail(long accountId, long fileId)
    {
        Map<String, Object> row = requireFile(accountId, fileId);
        if (!("UPLOADING".equals(row.get("status")) || "DELETE_PENDING".equals(row.get("status")))
            || !"RESERVED".equals(row.get("state"))) return;
        long bytes = number(row, "sizeBytes"), reservationId = number(row, "reservationId");
        if (mapper.failStorageFile(accountId, fileId) != 1 || mapper.releaseStorageReservation(reservationId) != 1
            || mapper.releaseStorageBalance(accountId, bytes) != 1)
            throw new ServiceException("文件存储额度释放失败", 409);
        entry(accountId, fileId, reservationId, "release", "RELEASE", 0, -bytes);
    }

    @Override
    public void free(long accountId, long fileId)
    {
        Map<String, Object> row = requireFile(accountId, fileId);
        if (!"DELETED".equals(row.get("status")) || !"SETTLED".equals(row.get("state"))) return;
        if (mapper.countStorageFreeEntry(accountId, fileId) != 0) return;
        long bytes = number(row, "sizeBytes"), reservationId = number(row, "reservationId");
        if (mapper.freeStorageBalance(accountId, bytes) != 1)
            throw new ServiceException("文件存储额度归还失败", 409);
        entry(accountId, fileId, reservationId, "free", "STORAGE_FREE", -bytes, 0);
    }

    private Map<String, Object> requireFile(long accountId, long fileId)
    {
        Map<String, Object> row = mapper.storageFileForUpdate(accountId, fileId);
        if (row == null) throw new ServiceException("文件存储额度事实不存在", 409);
        return row;
    }

    private void entry(long accountId, long fileId, long reservationId, String action, String type,
        long used, long reserved)
    {
        mapper.insertStorageEntry(nextId(), accountId, reservationId,
            "storage:" + fileId + ":" + action, type, used, reserved);
    }

    private long nextId()
    {
        Long id = mapper.nextId();
        if (id == null || id <= 0) throw new IllegalStateException("无法生成存储额度流水标识");
        return id;
    }

    private static long number(Map<String, Object> row, String key)
    { return ((Number) row.get(key)).longValue(); }
}
