package com.ruoyi.system.operations.service;

public interface ITtsQuotaService
{
    long reserve(long accountId, long applicationId, String businessId, long units);
    void finish(long accountId, String businessId, String outcome);
}
