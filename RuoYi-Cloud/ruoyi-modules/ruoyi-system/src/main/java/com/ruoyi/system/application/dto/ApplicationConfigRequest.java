package com.ruoyi.system.application.dto;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApplicationConfigRequest(@NotBlank String mode, @NotBlank String avatarVersionId, @NotBlank String voiceVersionId,
        String llmRelayVersionId, String asrRelayVersionId, @Valid @NotNull ContextPolicy contextPolicy,
        Map<String, Object> runtimeLimits, List<Object> skills)
{
    public record ContextPolicy(@NotNull Boolean enabled) { }
}
