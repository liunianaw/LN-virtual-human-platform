package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record GenerationTerminalResultRequest(@NotNull @Positive Long accountId, @NotNull @Positive Long taskId,
    @NotNull @Positive Long stepId, @NotNull @Positive Long attemptId, @NotBlank @RequestId String workerId,
    @NotNull @Positive Long leaseEpoch, @NotBlank @Pattern(regexp = "UNKNOWN|FAILED") String state)
{
}
