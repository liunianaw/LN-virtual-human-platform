package com.ruoyi.system.operations.service.impl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.operations.mapper.UsageMapper;
import com.ruoyi.system.operations.service.IUsageService;
import com.ruoyi.system.operations.dto.AccountLimitsRequest;
import com.ruoyi.system.operations.dto.QuotaGrantRequest;
import com.ruoyi.common.security.utils.SecurityUtils;

@Service
public class UsageServiceImpl implements IUsageService
{
    private static final Set<String> CAPABILITIES = Set.of("GENERATION","LLM","ASR","TTS","TOOL","CONTEXT");
    private final UsageMapper mapper;
    public UsageServiceImpl(UsageMapper mapper) { this.mapper = mapper; }

    @Override public Map<String,Object> usage(long accountId, String applicationId, String from, String to,
        String capability, int pageNum, int pageSize)
    {
        Filters filter = filters(accountId, applicationId, from, to, capability, pageNum, pageSize);
        return page(mapper.daily(accountId, filter.applicationId, filter.from, filter.to, filter.capability,
            pageSize, filter.offset()).stream().map(UsageServiceImpl::stringIds).toList(),
            mapper.dailyCount(accountId, filter.applicationId, filter.from, filter.to, filter.capability), pageNum, pageSize);
    }

    @Override public Map<String,Object> calls(long accountId, String applicationId, String from, String to,
        String capability, String status, int pageNum, int pageSize)
    {
        Filters filter = filters(accountId, applicationId, from, to, capability, pageNum, pageSize);
        if (status != null && !Set.of("STARTED","SUCCEEDED","FAILED","UNKNOWN","CANCELLED").contains(status))
            throw bad("调用状态无效");
        return page(mapper.calls(accountId, filter.applicationId, filter.from, filter.to, filter.capability,
            status, pageSize, filter.offset()).stream().map(UsageServiceImpl::stringIds).toList(),
            mapper.callCount(accountId, filter.applicationId, filter.from, filter.to, filter.capability, status), pageNum, pageSize);
    }

    @Override public Map<String,Object> limits(long accountId)
    {
        if (accountId <= 0) throw bad("账号无效");
        Map<String,Object> limits = mapper.limits(accountId);
        return Map.of("configured", limits != null, "limits", limits == null ? Map.of() : limits,
            "balances", mapper.balances(accountId));
    }

    @Override public Map<String,Object> reservations(long accountId, String state, int pageNum, int pageSize)
    {
        checkPage(accountId, pageNum, pageSize);
        if (state != null && !Set.of("RESERVED","SETTLED","RELEASED","REVIEW_REQUIRED").contains(state))
            throw bad("额度状态无效");
        return page(mapper.reservations(accountId, state, pageSize, (pageNum - 1) * pageSize)
            .stream().map(UsageServiceImpl::stringIds).toList(),
            mapper.reservationCount(accountId, state), pageNum, pageSize);
    }

    @Override public Map<String,Object> adminLimits(long accountId)
    {
        administrator();
        if (accountId <= 0 || mapper.activeAccount(accountId) == null)
            throw new ServiceException("账号不存在或不可用",404);
        return limits(accountId);
    }

    /** Account lock serializes quota changes with other administrator operations. */
    @Override @Transactional public Map<String,Object> configureLimits(long accountId, AccountLimitsRequest input)
    {
        administrator();
        if (accountId <= 0 || input == null || input.revision() == null || input.revision() < 0
            || input.maxFileBytes() == null || input.maxFileBytes() < 0
            || input.maxSessions() == null || input.maxSessions() < 0
            || input.maxGenerationTasks() == null || input.maxGenerationTasks() < 0
            || input.maxTurns() == null || input.maxTurns() < 0) throw bad("账号限额参数无效");
        if (mapper.lockActiveAccount(accountId) == null) throw new ServiceException("账号不存在或不可用",404);
        Map<String,Object> current = mapper.limits(accountId);
        if (current == null)
        {
            if (input.revision() != 0) throw new ServiceException("账号限额修订号已变化",409);
            if (mapper.insertLimits(accountId,input.maxFileBytes(),input.maxSessions(),
                input.maxGenerationTasks(),input.maxTurns()) != 1)
                throw new ServiceException("账号限额已变化",409);
        }
        else
        {
            if (number(current.get("revision")) != input.revision()
                || mapper.updateLimits(accountId,input.revision(),input.maxFileBytes(),input.maxSessions(),
                    input.maxGenerationTasks(),input.maxTurns()) != 1)
                throw new ServiceException("账号限额修订号已变化",409);
        }
        return limits(accountId);
    }

    /** Grants integer platform units and records exactly one immutable ledger entry per request key. */
    @Override @Transactional public Map<String,Object> grantQuota(long operatorId, long accountId,
        QuotaGrantRequest input, String key)
    {
        administrator();
        if (operatorId <= 0 || accountId <= 0 || input == null || input.units() == null
            || input.units() <= 0 || input.units() > 1_000_000_000_000L
            || !Set.of("AVATAR_COUNT","TTS_CHAR","STORAGE_BYTE").contains(input.quotaType())
            || input.reason() == null || input.reason().isBlank() || input.reason().trim().length() > 500
            || key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("额度授予参数无效");
        if (mapper.lockActiveAccount(accountId) == null) throw new ServiceException("账号不存在或不可用",404);
        String eventKey = "admin:grant:" + key;
        String reason = input.reason().trim();
        Map<String,Object> previous = mapper.grantByKey(accountId,eventKey);
        if (previous != null)
        {
            if (!input.quotaType().equals(previous.get("quotaType"))
                || input.units() != number(previous.get("units")) || !reason.equals(previous.get("reason")))
                throw new ServiceException("同一幂等键的授予参数不同",409);
            return Map.of("applied",false,"balances",mapper.balances(accountId));
        }
        Map<String,Object> balance = mapper.quotaBalanceForUpdate(accountId,input.quotaType());
        if (balance == null)
        {
            if (mapper.insertQuotaBalance(accountId,input.quotaType(),input.units()) != 1)
                throw new ServiceException("额度余额已变化",409);
        }
        else if (number(balance.get("grantedUnits")) > Long.MAX_VALUE - input.units()
            || mapper.addGrantedUnits(accountId,input.quotaType(),input.units()) != 1)
            throw new ServiceException("额度余额已变化",409);
        mapper.insertGrantEntry(accountId,input.quotaType(),eventKey,input.units(),operatorId,reason);
        return Map.of("applied",true,"balances",mapper.balances(accountId));
    }

    /** Rebuild one UTC day from immutable per-call rows under the same account lock as ingestion. */
    @Override @Transactional public Map<String,Object> rebuild(long accountId, LocalDate date)
    {
        if (accountId <= 0 || date == null || date.isAfter(LocalDate.now(java.time.ZoneOffset.UTC)))
            throw bad("核对日期无效");
        if (mapper.lockAccount(accountId) == null) throw bad("账号不存在");
        int removed = mapper.deleteDaily(accountId, date);
        int inserted = mapper.rebuildDaily(accountId, date);
        return Map.of("accountId",Long.toString(accountId),"usageDate",date.toString(),
            "previousBuckets",removed,"rebuiltBuckets",inserted);
    }

    @Override @Transactional public Map<String,Object> reviewReservation(long operatorId, long reservationId,
        String decision, String reason)
    {
        if (operatorId <= 0 || reservationId <= 0 || !Set.of("SETTLE","RELEASE").contains(decision)
            || reason == null || reason.isBlank() || reason.trim().length() > 500) throw bad("额度核对参数无效");
        Map<String,Object> pre = mapper.reservation(reservationId);
        if (pre == null) throw new ServiceException("预占不存在",404);
        long accountId = number(pre.get("accountId"));
        if (mapper.lockAccount(accountId) == null
            || mapper.balanceForUpdate(accountId, String.valueOf(pre.get("quotaType"))) == null)
            throw new ServiceException("额度余额不存在",409);
        Map<String,Object> row = mapper.reservationForUpdate(reservationId);
        if (row == null || number(row.get("accountId")) != accountId
            || !row.get("quotaType").equals(pre.get("quotaType"))) throw new ServiceException("预占已变化",409);
        String target = "SETTLE".equals(decision) ? "SETTLED" : "RELEASED";
        if (target.equals(row.get("state"))) return Map.of("reservationId",Long.toString(reservationId),"state",target);
        if (!"REVIEW_REQUIRED".equals(row.get("state"))) throw new ServiceException("只有待核对预占可处理",409);
        long reserved = number(row.get("reservedUnits")), settled = "SETTLE".equals(decision) ? reserved : 0;
        if (mapper.finishReservation(reservationId, target, settled) != 1
            || mapper.finishBalance(accountId, String.valueOf(row.get("quotaType")), reserved, settled) != 1)
            throw new ServiceException("额度并发状态已变化",409);
        mapper.insertReviewEntry(accountId, String.valueOf(row.get("quotaType")), reservationId,
            target.equals("SETTLED") ? "SETTLE" : "RELEASE", reserved, settled, operatorId, reason.trim());
        return Map.of("reservationId",Long.toString(reservationId),"state",target);
    }

    private static Filters filters(long accountId, String applicationId, String from, String to,
        String capability, int pageNum, int pageSize)
    {
        checkPage(accountId, pageNum, pageSize);
        Long app = null;
        if (applicationId != null && !applicationId.isBlank())
        {
            try { app = Long.parseLong(applicationId); if (app <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException error) { throw bad("应用标识无效"); }
        }
        if (capability != null && !CAPABILITIES.contains(capability)) throw bad("能力筛选无效");
        LocalDate end = date(to, LocalDate.now(java.time.ZoneOffset.UTC));
        LocalDate start = date(from, end.minusDays(6));
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start,end) > 30) throw bad("最多查询31天");
        return new Filters(app,start,end,capability,pageNum,pageSize);
    }
    private static LocalDate date(String value, LocalDate fallback)
    { try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
      catch (Exception error) { throw bad("日期应为 YYYY-MM-DD"); } }
    private static void checkPage(long accountId, int pageNum, int pageSize)
    { if (accountId <= 0 || pageNum < 1 || pageSize < 1 || pageSize > 100 || (long)(pageNum - 1) * pageSize > Integer.MAX_VALUE)
        throw bad("分页参数无效"); }
    private static Map<String,Object> page(List<Map<String,Object>> rows, long total, int pageNum, int pageSize)
    { return Map.of("items",rows,"total",total,"pageNum",pageNum,"pageSize",pageSize); }
    private static Map<String,Object> stringIds(Map<String,Object> source)
    {
        Map<String,Object> copy = new LinkedHashMap<>(source);
        for (String key : List.of("callId","applicationId","reservationId","businessId"))
            if (copy.get(key) instanceof Number value) copy.put(key, value.toString());
        if (copy.get("usageDate") instanceof java.sql.Date value) copy.put("usageDate", value.toLocalDate().toString());
        return copy;
    }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static void administrator()
    { if (!SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可配置账号额度",403); }
    private static ServiceException bad(String message) { return new ServiceException(message,400); }
    private record Filters(Long applicationId, LocalDate from, LocalDate to, String capability, int pageNum, int pageSize)
    { int offset() { return (pageNum - 1) * pageSize; } }
}
