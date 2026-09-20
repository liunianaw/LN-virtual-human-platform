package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GenerationPreflightReleaseRequest(@NotNull @Positive Long accountId, @NotNull @Positive Long taskId,
    @NotNull @Positive Long stepId, @NotBlank @RequestId String workerId, @NotNull @Positive Long leaseEpoch,
    @NotBlank @Size(max = 64) String errorCode)
{
}
