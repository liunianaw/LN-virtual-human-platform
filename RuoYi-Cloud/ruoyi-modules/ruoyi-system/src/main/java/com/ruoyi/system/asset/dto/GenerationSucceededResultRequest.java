package com.ruoyi.system.asset.dto;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GenerationSucceededResultRequest(@NotNull @Positive Long accountId, @NotNull @Positive Long taskId,
    @NotNull @Positive Long stepId, @NotNull @Positive Long attemptId, @NotBlank @RequestId String workerId,
    @NotNull @Positive Long leaseEpoch, @NotEmpty List<@Valid GenerationStoredObject> objects,
    @NotEmpty Map<String, Object> manifest)
{
}
