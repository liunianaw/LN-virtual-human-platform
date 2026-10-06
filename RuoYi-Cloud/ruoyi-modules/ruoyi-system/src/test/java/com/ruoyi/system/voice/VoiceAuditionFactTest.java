package com.ruoyi.system.voice;

import com.ruoyi.system.operations.CallFactEvent;
import com.ruoyi.system.operations.OperationsService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoiceAuditionFactTest
{
    @Test void auditionEventsPassActualIngressValidationAndKeepStableDistinctIds() throws Exception
    {
        var validate = OperationsService.class.getDeclaredMethod("validateEvent", CallFactEvent.class);
        validate.setAccessible(true);
        var events = new java.util.ArrayList<CallFactEvent>();
        var operations = mock(OperationsService.class);
        when(operations.accept(any())).thenAnswer(call -> {
            CallFactEvent event = call.getArgument(0);
            validate.invoke(null, event);
            events.add(event);
            return Map.of("accepted", true);
        });
        var service = new VoiceServiceImpl(null, null, null, operations, null);
        var fact = VoiceServiceImpl.class.getDeclaredMethod("auditionFact", long.class, String.class,
            String.class, int.class, String.class);
        fact.setAccessible(true);
        String operation = "audition-" + "a".repeat(48);
        for (String status : List.of("STARTED", "SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "STARTED"))
            fact.invoke(service, 1L, operation, status, 38, null);
        assertEquals(5, new HashSet<>(events.stream().map(CallFactEvent::eventId).toList()).size());
        assertEquals(events.get(0).eventId(), events.get(5).eventId());
        assertTrue(events.stream().allMatch(event -> operation.equals(event.operationKey())));
    }
}
