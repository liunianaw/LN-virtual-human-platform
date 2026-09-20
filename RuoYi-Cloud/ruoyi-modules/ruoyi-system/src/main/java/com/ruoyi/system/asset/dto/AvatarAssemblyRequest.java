package com.ruoyi.system.asset.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AvatarAssemblyRequest(@NotBlank @RequestId String requestId,
    @NotNull @PositiveOrZero Long expectedCandidateRevision,
    @NotEmpty @Size(min = 8, max = 8) List<@Valid AvatarSelectedResult> selectedResults) { }
