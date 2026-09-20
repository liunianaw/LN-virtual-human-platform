package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GenerationProgressRequest(@NotNull @Positive Long accountId, @NotNull @Positive Long taskId,
    @NotNull @Positive Long stepId, @NotNull @Positive Long attemptId, @NotBlank @RequestId String workerId,
    @NotNull @Positive Long leaseEpoch, @NotBlank @Pattern(regexp = "RUNNING|POLLING|UNKNOWN|FAILED") String state,
    @Size(max = 128) String providerRequestId, @Size(max = 64) String errorCode)
{
}
