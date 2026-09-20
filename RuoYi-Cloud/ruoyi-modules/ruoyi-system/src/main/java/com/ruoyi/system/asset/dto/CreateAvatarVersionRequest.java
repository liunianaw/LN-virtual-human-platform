package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateAvatarVersionRequest(@NotBlank @RequestId String requestId,
    @NotNull @Positive Long expectedAvatarRevision, @Positive Long baseVersionId, @Positive Long sourceFileId,
    @Positive Long officialServiceId, @Positive Long expectedServiceRevision) { }
