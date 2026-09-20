package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record GenerationAttemptRequest(@NotNull @Positive Long accountId, @NotNull @Positive Long taskId,
    @NotNull @Positive Long stepId, @NotBlank @RequestId String workerId, @NotNull @Positive Long leaseEpoch,
    @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String requestHash)
{
}
