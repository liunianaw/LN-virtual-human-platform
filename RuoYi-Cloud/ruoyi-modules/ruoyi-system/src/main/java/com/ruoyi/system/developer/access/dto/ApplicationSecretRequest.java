package com.ruoyi.system.developer.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApplicationSecretRequest(@NotBlank @Size(max = 100) String name) {}
