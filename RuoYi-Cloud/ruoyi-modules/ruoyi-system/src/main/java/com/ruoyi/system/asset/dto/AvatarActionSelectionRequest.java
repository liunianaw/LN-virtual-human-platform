package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record AvatarActionSelectionRequest(@NotBlank @RequestId String requestId, @NotNull @Positive Long resultId,
    @NotNull @PositiveOrZero Long expectedActionRevision, @NotNull Boolean visualAccepted) { }
