package com.ruoyi.system.operations.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Full replacement of one account's admission limits; revision 0 creates the first row. */
public record AccountLimitsRequest(
    @NotNull @PositiveOrZero Long revision,
    @NotNull @PositiveOrZero Long maxFileBytes,
    @NotNull @PositiveOrZero Integer maxSessions,
    @NotNull @PositiveOrZero Integer maxGenerationTasks,
    @NotNull @PositiveOrZero Integer maxTurns) { }
