package com.ruoyi.system.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminApplicationRestrictionRequest(@NotNull Boolean disabled, @NotBlank @Size(max = 500) String reason) {}
