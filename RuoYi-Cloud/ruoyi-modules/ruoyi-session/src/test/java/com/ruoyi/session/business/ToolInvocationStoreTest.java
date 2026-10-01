package com.ruoyi.session.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolInvocationStoreTest
{
    @Test
    void submittedUncertainRequestKeepsUnknownFactsAndSqlBindingsMatch()
    {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyLong())).thenReturn(8L);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object[] arguments = (Object[]) invocation.getRawArguments()[1];
            assertEquals(sql.chars().filter(character -> character == '?').count(), (long) arguments.length);
            if (sql.startsWith("update ")) assertEquals("UNKNOWN", arguments[0]);
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        new ToolInvocationStore(jdbc, new ObjectMapper()).fail(7,9,10,11,"TOOL_OUTCOME_UNKNOWN",true);
        verify(jdbc, times(3)).update(anyString(), any(Object[].class));
    }
}
