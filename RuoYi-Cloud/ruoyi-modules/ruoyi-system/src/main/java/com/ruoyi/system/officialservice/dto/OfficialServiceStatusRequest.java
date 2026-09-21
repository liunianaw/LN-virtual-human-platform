package com.ruoyi.system.officialservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class OfficialServiceStatusRequest
{
    @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") private String status;
    @NotBlank @Size(min = 1, max = 500) private String reason;
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public String getReason() { return reason; } public void setReason(String value) { reason = value; }
}
