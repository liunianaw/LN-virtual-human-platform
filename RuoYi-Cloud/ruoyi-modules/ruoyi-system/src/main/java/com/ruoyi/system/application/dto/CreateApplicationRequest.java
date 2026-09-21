package com.ruoyi.system.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApplicationRequest(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) { }
