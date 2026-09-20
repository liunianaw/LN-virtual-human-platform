package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GenerationClaimRequest(@NotNull @Positive Long accountId, @NotNull @Positive Long taskId,
    @NotBlank @RequestId String workerId)
{
}
