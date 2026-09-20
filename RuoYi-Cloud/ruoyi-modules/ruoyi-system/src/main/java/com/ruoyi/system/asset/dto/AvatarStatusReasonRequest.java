package com.ruoyi.system.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员变更公共 Avatar 状态时必须留下的原因。 */
public record AvatarStatusReasonRequest(@NotBlank @Size(max = 500) String reason) { }
