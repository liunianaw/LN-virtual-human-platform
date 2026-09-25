package com.ruoyi.system.asset.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.impl.AssetStorageQuotaServiceImpl;

class AssetStorageQuotaServiceTest
{
    @Test
    void reservesOnlyWithinConfiguredFileAndStorageLimits()
    {
        AssetMapper mapper = mock(AssetMapper.class);
        AssetStorageQuotaServiceImpl quota = new AssetStorageQuotaServiceImpl(mapper);
        when(mapper.maxFileBytesForUpdate(7L)).thenReturn(10L);
        when(mapper.reserveStorageBalance(7L, 8L)).thenReturn(1);
        when(mapper.nextId()).thenReturn(21L, 22L);
        assertEquals(21L, quota.reserve(7L, 11L, 8L));
        verify(mapper).insertStorageReservation(21L, 7L, 11L, 8L);
        verify(mapper).insertStorageEntry(22L, 7L, 21L, "storage:11:reserve", "RESERVE", 0L, 8L);
        assertEquals(429, assertThrows(ServiceException.class, () -> quota.reserve(7L, 12L, 11L)).getCode());
        verify(mapper, never()).reserveStorageBalance(7L, 11L);
    }

    @Test
    void settlesAvailableFileAndReleasesFailedUpload()
    {
        AssetMapper mapper = mock(AssetMapper.class);
        AssetStorageQuotaServiceImpl quota = new AssetStorageQuotaServiceImpl(mapper);
        when(mapper.storageFileForUpdate(7L, 11L)).thenReturn(file("UPLOADING"));
        when(mapper.settleStorageReservation(21L, 8L)).thenReturn(1);
        when(mapper.settleStorageBalance(7L, 8L)).thenReturn(1);
        when(mapper.completeStorageFile(7L, 11L)).thenReturn(1);
        when(mapper.nextId()).thenReturn(22L);
        quota.complete(7L, 11L);
        verify(mapper).insertStorageEntry(22L, 7L, 21L, "storage:11:settle", "SETTLE", 8L, -8L);

        when(mapper.storageFileForUpdate(7L, 12L)).thenReturn(file("DELETE_PENDING"));
        when(mapper.failStorageFile(7L, 12L)).thenReturn(1);
        when(mapper.releaseStorageReservation(21L)).thenReturn(1);
        when(mapper.releaseStorageBalance(7L, 8L)).thenReturn(1);
        quota.fail(7L, 12L);
        verify(mapper).insertStorageEntry(22L, 7L, 21L, "storage:12:release", "RELEASE", 0L, -8L);
    }

    private static Map<String, Object> file(String status)
    { return Map.of("status", status, "state", "RESERVED", "sizeBytes", 8L, "reservationId", 21L); }
}
