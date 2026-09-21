package com.ruoyi.system.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApplicationStatusRequest(@NotBlank String status, @NotBlank @Size(min = 1, max = 500) String reason) { }
