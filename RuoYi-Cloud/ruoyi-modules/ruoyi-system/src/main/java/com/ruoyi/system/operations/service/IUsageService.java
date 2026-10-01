package com.ruoyi.system.operations.service;

import java.time.LocalDate;
import java.util.Map;
import com.ruoyi.system.operations.dto.AccountLimitsRequest;
import com.ruoyi.system.operations.dto.QuotaGrantRequest;

public interface IUsageService
{
    Map<String,Object> usage(long accountId, String applicationId, String from, String to,
        String capability, int pageNum, int pageSize);
    Map<String,Object> calls(long accountId, String applicationId, String from, String to,
        String capability, String status, int pageNum, int pageSize);
    Map<String,Object> limits(long accountId);
    Map<String,Object> reservations(long accountId, String state, int pageNum, int pageSize);
    Map<String,Object> adminOverview(String from, String to);
    Map<String,Object> adminAccounts(String keyword, String from, String to, int pageNum, int pageSize);
    Map<String,Object> adminAccountUsage(long accountId, String from, String to);
    Map<String,Object> rebuild(long accountId, LocalDate date);
    Map<String,Object> reviewReservation(long operatorId, long reservationId, String decision, String reason);
    Map<String,Object> adminLimits(long accountId);
    Map<String,Object> configureLimits(long accountId, AccountLimitsRequest request);
    Map<String,Object> grantQuota(long operatorId, long accountId, QuotaGrantRequest request, String idempotencyKey);
}
