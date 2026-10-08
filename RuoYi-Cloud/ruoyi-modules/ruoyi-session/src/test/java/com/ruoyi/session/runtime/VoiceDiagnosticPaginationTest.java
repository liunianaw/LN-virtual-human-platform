package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.runtime.mapper.VoiceTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoiceDiagnosticPaginationTest
{
    @Test
    void diagnosticPagesShareFiltersAndValidateBoundsBeforeQuerying()
    {
        var mapper = mock(VoiceTaskMapper.class);
        var store = mock(VoiceAttemptStore.class);
        when(store.mapper()).thenReturn(mapper);
        var service = new VoiceOrchestrationServiceImpl(store, null, null, null, null, null, null, new ObjectMapper());
        when(mapper.pageDiagnostics(7L, 9L, "FAILED", 20, 20)).thenReturn(List.of());
        when(mapper.countDiagnostics(7L, 9L, "FAILED")).thenReturn(35L);
        var page = service.pageDiagnostics(7L, 2, 20, 9L, " FAILED ");
        assertEquals(35L, page.get("total"));
        verify(mapper).pageDiagnostics(7L, 9L, "FAILED", 20, 20);
        verify(mapper).countDiagnostics(7L, 9L, "FAILED");
        assertThrows(RuntimeProblem.class, () -> service.pageDiagnostics(7L, Integer.MAX_VALUE, 100, null, null));
        assertThrows(RuntimeProblem.class, () -> service.pageDiagnostics(7L, 1, 101, null, null));
        assertThrows(RuntimeProblem.class, () -> service.pageDiagnostics(7L, 1, 20, null, "FAILED&accountId=8"));
        verifyNoMoreInteractions(mapper);
    }
}
