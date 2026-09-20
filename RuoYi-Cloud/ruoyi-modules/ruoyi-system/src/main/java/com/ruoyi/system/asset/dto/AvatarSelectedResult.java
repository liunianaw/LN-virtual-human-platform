package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record AvatarSelectedResult(@NotBlank String actionCode, @Positive Long resultId) { }
