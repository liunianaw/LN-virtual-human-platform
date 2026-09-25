package com.ruoyi.system.application.dto;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApplicationConfigRequest(@NotBlank String mode, @NotBlank String avatarVersionId, @NotBlank String voiceVersionId,
        String llmRelayVersionId, String asrRelayVersionId, String llmModelId, String systemPrompt,
        Map<String, Object> llmParameters, Map<String, Boolean> llmCapabilities,
        @NotNull Map<String, Object> contextPolicy, Map<String, Object> runtimeLimits, List<SkillBinding> skills)
{
    public record SkillBinding(@NotBlank String skillVersionId, boolean enabled, int sortOrder) { }
}
