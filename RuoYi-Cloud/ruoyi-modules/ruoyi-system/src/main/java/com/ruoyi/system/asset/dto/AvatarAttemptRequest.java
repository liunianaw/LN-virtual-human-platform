package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AvatarAttemptRequest(@NotBlank @RequestId String requestId,
    @NotNull @PositiveOrZero Long expectedActionRevision) { }
