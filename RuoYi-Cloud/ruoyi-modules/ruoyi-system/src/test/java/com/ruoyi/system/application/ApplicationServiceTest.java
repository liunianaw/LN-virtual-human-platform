package com.ruoyi.system.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
    private static final ApplicationConfigRequest CHAT = new ApplicationConfigRequest(
        "CHAT", "11", "12", "13", null, "model-a", "Be helpful",
        Map.of("temperature", 0.7, "maxOutputTokens", 512), Map.of("image", false, "tool", false),
        Map.of("enabled", false), Map.of(), List.of());

    @Test
    void chatPublishRequiresOfficialVoiceAndAuthorizedLlmBeforeWritingVersion()
    {
        ApplicationMapper mapper = mock(ApplicationMapper.class);
        ApplicationServiceImpl service = new ApplicationServiceImpl(mapper, new ObjectMapper());
        when(mapper.selectIdempotencyForUpdate(anyLong(), any(), any())).thenReturn(null);
        when(mapper.selectApplicationForUpdate(7, 9)).thenReturn(
            Map.of("status", "ACTIVE", "adminDisabled", 0, "revision", 1L));
        when(mapper.countAvailableAvatarVersion(7, 11)).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.publish(7, 9, CHAT, "1", "voice-missing"));
        assertTrue(org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
            .noneMatch(call -> "insertConfig".equals(call.getMethod().getName())));
        when(mapper.countAvailableOfficialVoiceVersion(12)).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.publish(7, 9, CHAT, "1", "relay-missing"));
        when(mapper.selectRelayBindingForUpdate(7, 9, 13, "llm")).thenReturn(
            Map.of("versionId", "13", "capabilities", "{\"llm\":true}"));
        when(mapper.nextConfigVersionNo(9)).thenReturn(1);
        when(mapper.nextId()).thenReturn(100L);
        when(mapper.replaceCurrentConfig(eq(9L), eq(100L), any(), eq(1L))).thenReturn(1);
        when(mapper.selectConfig(7, 9, 100)).thenReturn(Map.of(
            "configVersionId", "100", "versionNo", 1, "configHash", "abc",
            "mode", "CHAT", "runtimeLimits", "{}"));
        Map<String, Object> result = service.publish(7, 9, CHAT, "1", "valid-chat");
        assertEquals("100", result.get("configVersionId"));
        assertTrue(org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
            .anyMatch(call -> "insertConfig".equals(call.getMethod().getName())
                && "CHAT".equals(call.getArgument(4))));
    }
    @Test
    void duplicateToolNamesCannotBecomeAnApplicationConfiguration()
    {
        ApplicationMapper mapper = mock(ApplicationMapper.class);
        ApplicationServiceImpl service = new ApplicationServiceImpl(mapper, new ObjectMapper());
        when(mapper.selectIdempotencyForUpdate(anyLong(), any(), any())).thenReturn(null);
        when(mapper.selectApplicationForUpdate(7, 9)).thenReturn(
            Map.of("status", "ACTIVE", "adminDisabled", 0, "revision", 1L));
        when(mapper.countAvailableAvatarVersion(7, 11)).thenReturn(1);
        when(mapper.countAvailableOfficialVoiceVersion(12)).thenReturn(1);
        when(mapper.selectRelayBindingForUpdate(7, 9, 13, "llm")).thenReturn(
            Map.of("versionId", "13", "capabilities", "{\"llm\":true,\"tool\":true}"));
        Map<String, Object> tool = Map.of("versionId", "20", "skillType", "HTTP_TOOL",
            "toolName", "lookup", "contextRequirements", "{}");
        when(mapper.selectSkillBindingForUpdate(7, 20)).thenReturn(tool);
        when(mapper.selectSkillBindingForUpdate(7, 21)).thenReturn(tool);
        ApplicationConfigRequest input = new ApplicationConfigRequest(
            "CHAT", "11", "12", "13", null, "model-a", null, Map.of(),
            Map.of("tool", true), Map.of("enabled", false), Map.of(),
            List.of(new ApplicationConfigRequest.SkillBinding("20", true, 0),
                new ApplicationConfigRequest.SkillBinding("21", true, 1)));
        assertThrows(ServiceException.class, () -> service.publish(7, 9, input, "1", "same-tool"));
        assertTrue(org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
            .noneMatch(call -> "insertConfig".equals(call.getMethod().getName())));
    }

}
