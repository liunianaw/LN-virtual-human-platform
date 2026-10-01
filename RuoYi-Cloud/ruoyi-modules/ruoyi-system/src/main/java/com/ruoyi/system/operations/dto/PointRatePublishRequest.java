package com.ruoyi.system.operations.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record PointRatePublishRequest(
    @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal generationActionPoints,
    @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal ttsCharacterPoints,
    @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal storageBytePoints,
    LocalDateTime effectiveAt) { }
