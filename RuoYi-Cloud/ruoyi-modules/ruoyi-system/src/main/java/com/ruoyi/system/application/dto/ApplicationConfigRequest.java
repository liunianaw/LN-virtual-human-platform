package com.ruoyi.system.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The complete current Application configuration. Saving replaces the prior value atomically. */
public record ApplicationConfigRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1000) String description,
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String avatarId,
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String voiceId,
    @Size(max = 32768) String systemPrompt,
    @Valid @Size(max = 20) List<SkillBinding> skills)
{
    public record SkillBinding(
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String skillId,
        @PositiveOrZero int sortOrder) { }
}
