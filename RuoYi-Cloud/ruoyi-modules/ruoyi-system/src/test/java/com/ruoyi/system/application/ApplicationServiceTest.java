package com.ruoyi.system.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.application.dto.ApplicationConfigRequest;
import com.ruoyi.system.application.mapper.ApplicationMapper;
import com.ruoyi.system.application.service.impl.ApplicationServiceImpl;

class ApplicationServiceTest
{
    @Test
    void currentSaveRejectsStaleRevisionUnavailableVoiceAndDuplicateToolNames()
    {
        ApplicationMapper mapper = mock(ApplicationMapper.class);
        ApplicationServiceImpl service = new ApplicationServiceImpl(mapper, new ObjectMapper());
        when(mapper.selectApplicationForUpdate(7,9)).thenReturn(Map.of("revision",2L,"status","ACTIVE"));
        var plain = new ApplicationConfigRequest("app",null,"11","12",null,List.of());
        assertThrows(ServiceException.class, () -> service.update(7,9,plain,"1","stale"));
        when(mapper.countAvailableAvatar(7,11)).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.update(7,9,plain,"2","voice-missing"));
        when(mapper.countAvailableVoice(12)).thenReturn(1);
        when(mapper.selectSkillBindingForUpdate(7,20)).thenReturn(Map.of("toolName","lookup"));
        when(mapper.selectSkillBindingForUpdate(7,21)).thenReturn(Map.of("toolName","lookup"));
        var duplicate = new ApplicationConfigRequest("app",null,"11","12",null,
            List.of(new ApplicationConfigRequest.SkillBinding("20",0),new ApplicationConfigRequest.SkillBinding("21",1)));
        assertThrows(ServiceException.class, () -> service.update(7,9,duplicate,"2","same-tool"));
        verify(mapper,never()).updateCurrent(anyLong(),any(),any(),anyLong(),anyLong(),any(),anyLong());
    }
}
