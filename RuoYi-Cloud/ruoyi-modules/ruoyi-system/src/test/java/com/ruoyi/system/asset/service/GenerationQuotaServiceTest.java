package com.ruoyi.system.asset.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.impl.GenerationQuotaServiceImpl;
import com.ruoyi.system.operations.service.PointBillingService;

class GenerationQuotaServiceTest
{
    @Test
    void admissionReservesOneUnitAndRejectsExceededConcurrency()
    {
        AssetMapper mapper = mock(AssetMapper.class);
        PointBillingService billing = mock(PointBillingService.class);
        GenerationQuotaServiceImpl quota = new GenerationQuotaServiceImpl(mapper,billing);
        when(mapper.maxGenerationTasksForUpdate(7L)).thenReturn(1);
        when(mapper.countActiveGenerationTasks(7L)).thenReturn(0, 1);
        quota.reserve(7L, 11L, 21L, 8);

        verify(billing).reserve(7L,21L,"GENERATION","11",1,PointBillingService.GENERATION_ACTION,8);
        assertEquals(429, assertThrows(ServiceException.class, () -> quota.reserve(7L, 12L, 22L, 1)).getCode());
    }

    @Test
    void terminalSuccessSettlesAndUnknownFailureKeepsReservationForReview()
    {
        AssetMapper mapper = mock(AssetMapper.class);
        PointBillingService billing = mock(PointBillingService.class);
        GenerationQuotaServiceImpl quota = new GenerationQuotaServiceImpl(mapper,billing);
        when(mapper.taskReservation(7L, 11L)).thenReturn(row("SUCCEEDED", 0));

        quota.finish(7L, 11L);

        verify(billing).finish(7L,"GENERATION","11","SETTLE",8L);

        when(mapper.taskReservation(7L, 12L)).thenReturn(Map.of("reservationId", 22L,
            "reservedUnits", 5000L, "reservationNo", 1, "state", "RESERVED", "taskStatus", "FAILED",
            "unknownAttempts", 1, "successfulAttempts", 0, "measuredUnits", 1L, "ledgerType", "POINT"));
        quota.finish(7L, 12L);
        verify(billing).finish(7L,"GENERATION","12","REVIEW",0L);
    }

    private static Map<String, Object> row(String status, int unknown)
    { return Map.of("reservationId", 21L, "reservedUnits", 40000L, "reservationNo", 1,
        "state", "RESERVED", "taskStatus", status, "unknownAttempts", unknown,
        "successfulAttempts", 8, "measuredUnits", 8L, "ledgerType", "POINT"); }
}
