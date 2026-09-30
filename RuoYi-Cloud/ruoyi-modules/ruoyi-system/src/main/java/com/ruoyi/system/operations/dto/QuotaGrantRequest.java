package com.ruoyi.system.operations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Grants platform units, not money; the operator must check the provider budget separately. */
public record QuotaGrantRequest(
    @NotBlank String quotaType,
    @NotNull @Positive Long units,
    @NotBlank @Size(max = 500) String reason) { }
