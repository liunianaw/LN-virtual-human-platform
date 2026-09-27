package com.ruoyi.system.operations.service.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.operations.mapper.TtsQuotaMapper;
import com.ruoyi.system.operations.service.ITtsQuotaService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Official TTS character quota, keyed by the immutable Session turn and segment ordinal. */
@Service
public class TtsQuotaServiceImpl implements ITtsQuotaService
{
    private final TtsQuotaMapper mapper;
    public TtsQuotaServiceImpl(TtsQuotaMapper mapper) { this.mapper = mapper; }

    @Override
    @Transactional
    public long reserve(long accountId, long applicationId, String businessId, long units)
    {
        if (accountId <= 0 || applicationId <= 0 || businessId == null || !businessId.matches("[1-9][0-9]{0,18}:[0-9]{1,5}")
            || units <= 0 || units > 8000) throw new ServiceException("TTS 额度请求无效", 400);
        String purpose = mapper.applicationPurpose(accountId, applicationId);
        if ("VOICE_PREVIEW".equals(purpose)) return 0;
        if (!"USER".equals(purpose)) throw new ServiceException("TTS 应用不可用", 403);
        if (mapper.balanceForUpdate(accountId) == null) throw new ServiceException("TTS 额度不足", 429);
        Map<String, Object> old = mapper.reservation(accountId, businessId);
        if (old != null)
        {
            if (number(old.get("reservedUnits")) != units || "RELEASED".equals(old.get("state")))
                throw new ServiceException("TTS 段额度请求冲突", 409);
            return number(old.get("id"));
        }
        if (mapper.reserveBalance(accountId, units) != 1) throw new ServiceException("TTS 额度不足", 429);
        long id = nextId();
        mapper.insertReservation(id, accountId, businessId, units);
        entry(accountId, id, businessId + ":reserve", "RESERVE", 0, units);
        return id;
    }

    @Override
    @Transactional
    public void finish(long accountId, String businessId, String outcome)
    {
        if (accountId <= 0 || businessId == null || !businessId.matches("[1-9][0-9]{0,18}:[0-9]{1,5}")
            || outcome == null || !java.util.Set.of("SETTLE", "REVIEW", "RELEASE").contains(outcome))
            throw new ServiceException("TTS 额度状态无效", 400);
        // Reserve takes the balance lock before the reservation lock; finish uses the same order.
        if (mapper.balanceForUpdate(accountId) == null) throw new ServiceException("TTS 额度余额不存在", 404);
        Map<String, Object> row = mapper.reservationForUpdate(accountId, businessId);
        if (row == null) throw new ServiceException("TTS 额度预占不存在", 404);
        String next = switch (outcome) { case "SETTLE" -> "SETTLED"; case "RELEASE" -> "RELEASED"; default -> "REVIEW_REQUIRED"; };
        if (next.equals(row.get("state"))) return;
        if (!"RESERVED".equals(row.get("state"))) throw new ServiceException("TTS 额度终态冲突", 409);
        long id = number(row.get("id")), units = number(row.get("reservedUnits"));
        if ("REVIEW_REQUIRED".equals(next))
        {
            if (mapper.reviewReservation(id) != 1) throw new ServiceException("TTS 待核对状态已变化", 409);
            return;
        }
        long usedUnits = "SETTLED".equals(next) ? units : 0;
        if (mapper.finishReservation(id, next, usedUnits) != 1 || mapper.finishBalance(accountId, units, usedUnits) != 1)
            throw new ServiceException("TTS 额度终态已变化", 409);
        entry(accountId, id, businessId + ":" + outcome.toLowerCase(), usedUnits > 0 ? "SETTLE" : "RELEASE",
            usedUnits, -units);
    }

    private void entry(long accountId, long reservationId, String key, String type, long used, long reserved)
    { mapper.insertEntry(nextId(), accountId, reservationId, "tts:" + key, type, used, reserved); }
    private long nextId()
    {
        Long id = mapper.nextId();
        if (id == null || id <= 0) throw new IllegalStateException("Could not allocate TTS quota identifier");
        return id;
    }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
}
