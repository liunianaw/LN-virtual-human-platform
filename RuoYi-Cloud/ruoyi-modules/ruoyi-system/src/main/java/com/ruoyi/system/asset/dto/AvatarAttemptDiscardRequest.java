package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record AvatarAttemptDiscardRequest(@NotBlank @RequestId String requestId,
    @NotNull @PositiveOrZero Long expectedActionRevision, @NotNull @Positive Long retainResultId) { }
