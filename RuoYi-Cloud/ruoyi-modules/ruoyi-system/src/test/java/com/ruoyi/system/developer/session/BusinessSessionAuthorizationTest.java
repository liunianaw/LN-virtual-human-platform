package com.ruoyi.system.developer.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.developer.session.mapper.BusinessSessionMapper;
import org.junit.jupiter.api.Test;

class BusinessSessionAuthorizationTest
{
    @Test
    void recordingUsesAccountFileLimitAndPlatformCap() throws Exception
    {
        BusinessSessionMapper mapper = mock(BusinessSessionMapper.class);
        var service = new BusinessSessionAuthorization(mock(IAccessKeyService.class), mapper, new ObjectMapper());
        when(mapper.snapshot(7,8)).thenReturn(Map.of("accountStatus","0","accountDeleted","0",
            "applicationStatus","ACTIVE","adminDisabled",0));
        when(mapper.maxRecordingBytes(7)).thenReturn(2_097_152L);
        assertEquals(1_900_000L, service.recordingLimit(7,8));
        when(mapper.maxRecordingBytes(7)).thenReturn(1000L);
        assertEquals(1000L, service.recordingLimit(7,8));
        when(mapper.maxRecordingBytes(7)).thenReturn(0L);
        assertThrows(com.ruoyi.common.core.exception.ServiceException.class, () -> service.recordingLimit(7,8));
        try (var input = getClass().getResourceAsStream("/mapper/developer/BusinessSessionMapper.xml"))
        {
            String xml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xml.contains("select max_file_bytes from p_account_limit"));
            assertFalse(xml.contains("select max_recording_bytes"));
        }
    }

    @Test
    void currentChangesDoNotInvalidateFrozenResourcesAndSnapshotJsonIsStructured()
    {
        BusinessSessionMapper mapper = mock(BusinessSessionMapper.class);
        BusinessSessionAuthorization service = new BusinessSessionAuthorization(mock(IAccessKeyService.class), mapper, new ObjectMapper());
        Map<String,Object> row = new HashMap<>(Map.of("accountStatus","0","accountDeleted","0",
            "applicationStatus","ACTIVE","adminDisabled",0,"applicationRevision",3L));
        when(mapper.snapshot(7,8)).thenReturn(row);
        when(mapper.availableSession(7,8,9)).thenReturn(1);
        assertNotNull(service.checkSession(7,8,9));
        verify(mapper,never()).availableCurrent(anyLong(),anyLong());
        when(mapper.currentForUpdate(7,8)).thenReturn(Map.of("status","ACTIVE","adminDisabled",0,"applicationRevision",3L));
        when(mapper.availableCurrent(7,8)).thenReturn(1);
        when(mapper.maxSessionsForUpdate(7)).thenReturn(2);
        Map<String,Object> skill = new HashMap<>(Map.of("status","PUBLISHED","skillId",10L,"skillType","HTTP_TOOL",
            "inputSchema","{\"type\":\"object\"}","frontendFields","[\"name\"]","requiresUserCredential",1));
        when(mapper.skills(7,8)).thenReturn(List.of(skill));
        when(mapper.resources(8)).thenReturn(List.of());
        Map<String,Object> snapshot = service.reserve(7,8,3,9,"create");
        assertTrue(skill.get("inputSchema") instanceof JsonNode);
        assertEquals(Boolean.TRUE, skill.get("requiresUserCredential"));
        assertEquals("name", ((JsonNode) skill.get("frontendFields")).get(0).asText());
        assertNotNull(snapshot.get("developerConfig"));
    }
}
