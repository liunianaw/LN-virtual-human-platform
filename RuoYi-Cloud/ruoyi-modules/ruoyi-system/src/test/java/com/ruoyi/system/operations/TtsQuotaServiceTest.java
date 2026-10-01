package com.ruoyi.system.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.operations.mapper.TtsQuotaMapper;
import com.ruoyi.system.operations.service.PointBillingService;
import com.ruoyi.system.operations.service.impl.TtsQuotaServiceImpl;

class TtsQuotaServiceTest
{
    @Test
    void legacyReservationFinishesInTheOldLedgerWhileNewRequestsUsePoints()
    {
        TtsQuotaMapper mapper=mock(TtsQuotaMapper.class);
        PointBillingService billing=mock(PointBillingService.class);
        TtsQuotaServiceImpl service=new TtsQuotaServiceImpl(mapper,billing);
        when(mapper.applicationPurpose(7L,8L)).thenReturn("USER");
        when(mapper.reservation(7L,"11:1")).thenReturn(Map.of("id",41L,"reservedUnits",100L,"state","RESERVED"));

        assertEquals(41L,service.reserve(7L,8L,"11:1",100));
        verify(billing,never()).reserve(7L,41L,"TTS_SEGMENT","11:1",1,PointBillingService.TTS_CHARACTER,100L);

        when(mapper.reservation(7L,"11:2")).thenReturn(null);
        when(mapper.nextId()).thenReturn(42L);
        when(billing.reserve(7L,42L,"TTS_SEGMENT","11:2",1,PointBillingService.TTS_CHARACTER,100L)).thenReturn(42L);
        assertEquals(42L,service.reserve(7L,8L,"11:2",100));
    }
}
