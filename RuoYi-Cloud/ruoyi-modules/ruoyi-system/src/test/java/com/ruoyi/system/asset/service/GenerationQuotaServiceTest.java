package com.ruoyi.system.asset.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.impl.GenerationQuotaServiceImpl;

class GenerationQuotaServiceTest
{
    @Test
    void admissionReservesOneUnitAndRejectsExceededConcurrency()
    {
        AssetMapper mapper = mock(AssetMapper.class);
        GenerationQuotaServiceImpl quota = new GenerationQuotaServiceImpl(mapper);
        when(mapper.maxGenerationTasksForUpdate(7L)).thenReturn(1);
        when(mapper.countActiveGenerationTasks(7L)).thenReturn(0, 1);
        when(mapper.reserveAvatarQuota(7L)).thenReturn(1);
        when(mapper.nextId()).thenReturn(91L);

        quota.reserve(7L, 11L, 21L);

        verify(mapper).insertQuotaReservation(21L, 7L, 11L, 1);
        verify(mapper).insertQuotaEntry(91L, 7L, 21L, "generation:11:reserve:1", "RESERVE", 0, 1);
        assertEquals(429, assertThrows(ServiceException.class, () -> quota.reserve(7L, 12L, 22L)).getCode());
        verify(mapper, never()).insertQuotaReservation(eq(22L), anyLong(), anyLong(), eq(1));
    }

    @Test
    void terminalSuccessSettlesAndUnknownFailureKeepsReservationForReview()
    {
        AssetMapper mapper = mock(AssetMapper.class);
        GenerationQuotaServiceImpl quota = new GenerationQuotaServiceImpl(mapper);
        when(mapper.taskReservationForUpdate(7L, 11L)).thenReturn(row("SUCCEEDED", 0));
        when(mapper.settleQuotaReservation(21L)).thenReturn(1);
        when(mapper.settleAvatarQuota(7L)).thenReturn(1);
        when(mapper.nextId()).thenReturn(92L);

        quota.finish(7L, 11L);

        verify(mapper).insertQuotaEntry(92L, 7L, 21L, "generation:11:settle:1", "SETTLE", 1, -1);

        when(mapper.taskReservationForUpdate(7L, 12L)).thenReturn(Map.of("reservationId", 22L,
            "reservedUnits", 1L, "reservationNo", 1, "state", "RESERVED", "taskStatus", "FAILED", "unknownAttempts", 1));
        when(mapper.reviewQuotaReservation(22L)).thenReturn(1);
        quota.finish(7L, 12L);
        verify(mapper, never()).releaseAvatarQuota(7L);
        verify(mapper).reviewQuotaReservation(22L);
    }

    private static Map<String, Object> row(String status, int unknown)
    { return Map.of("reservationId", 21L, "reservedUnits", 1L, "reservationNo", 1,
        "state", "RESERVED", "taskStatus", status, "unknownAttempts", unknown); }
}
