package com.ruoyi.system.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.operations.mapper.PointBillingMapper;
import com.ruoyi.system.operations.service.PointBillingService;

class PointBillingServiceTest
{
    @Test
    void repeatedRequestKeepsTheOriginalRateSnapshot()
    {
        PointBillingMapper mapper=mock(PointBillingMapper.class);
        PointBillingService service=new PointBillingService(mapper);
        when(mapper.reservation(7L,"TTS_SEGMENT","11:1",1)).thenReturn(null,null,
            Map.of("id",21L,"measuredUnits",100L,"billingItem","TTS_CHARACTER","state","RESERVED"));
        when(mapper.currentRate()).thenReturn(Map.of("id",3L,"ttsCharacterCent",1L));
        when(mapper.balanceForUpdate(7L)).thenReturn(Map.of("grantedCent",1000L));
        when(mapper.reserveBalance(7L,100L)).thenReturn(1);
        when(mapper.nextId()).thenReturn(91L);

        assertEquals(21L,service.reserve(7L,21L,"TTS_SEGMENT","11:1",1,"TTS_CHARACTER",100));
        assertEquals(21L,service.reserve(7L,99L,"TTS_SEGMENT","11:1",1,"TTS_CHARACTER",100));

        verify(mapper).insertReservation(21L,7L,"TTS_SEGMENT","11:1",1,"TTS_CHARACTER",100L,1L,3L,100L);
    }
}
