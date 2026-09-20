package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Worker 已写入对象存储的受限元数据。 */
public record GenerationStoredObject(
    @NotBlank @Size(max = 512) String objectKey,
    @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256,
    @PositiveOrZero long sizeBytes,
    @NotBlank @Size(max = 128) String contentType)
{
}
