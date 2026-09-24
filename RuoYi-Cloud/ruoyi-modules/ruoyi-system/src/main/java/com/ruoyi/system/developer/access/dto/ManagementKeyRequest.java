package com.ruoyi.system.developer.access.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ManagementKeyRequest(@NotBlank @Size(max = 100) String name, @NotEmpty List<String> scopes) {}
