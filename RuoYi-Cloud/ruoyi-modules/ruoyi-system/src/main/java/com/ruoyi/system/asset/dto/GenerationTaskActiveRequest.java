package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GenerationTaskActiveRequest(@NotNull @Positive Long accountId, @NotNull @Positive Long taskId)
{
}
