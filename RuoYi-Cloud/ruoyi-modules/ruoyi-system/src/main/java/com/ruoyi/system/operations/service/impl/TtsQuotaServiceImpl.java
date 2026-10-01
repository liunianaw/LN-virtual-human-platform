package com.ruoyi.system.operations.service.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.operations.mapper.TtsQuotaMapper;
import com.ruoyi.system.operations.service.ITtsQuotaService;
import com.ruoyi.system.operations.service.PointBillingService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Official TTS character quota, keyed by the immutable Session turn and segment ordinal. */
@Service
public class TtsQuotaServiceImpl implements ITtsQuotaService
{
    private final TtsQuotaMapper mapper;
    private final PointBillingService billing;
    public TtsQuotaServiceImpl(TtsQuotaMapper mapper, PointBillingService billing)
    { this.mapper = mapper; this.billing = billing; }

    @Override
    @Transactional
    public long reserve(long accountId, long applicationId, String businessId, long units)
    {
        if (accountId <= 0 || applicationId <= 0 || businessId == null || !businessId.matches("[1-9][0-9]{0,18}:[0-9]{1,5}")
            || units <= 0 || units > 8000) throw new ServiceException("TTS 额度请求无效", 400);
        String purpose = mapper.applicationPurpose(accountId, applicationId);
        if ("VOICE_PREVIEW".equals(purpose)) return 0;
        if (!"USER".equals(purpose)) throw new ServiceException("TTS 应用不可用", 403);
        Map<String,Object> legacy = mapper.reservation(accountId,businessId);
        if (legacy != null)
        {
            if (number(legacy.get("reservedUnits")) != units || "RELEASED".equals(legacy.get("state")))
                throw new ServiceException("TTS 段额度请求冲突",409);
            return number(legacy.get("id"));
        }
        long id = nextId();
        return billing.reserve(accountId,id,"TTS_SEGMENT",businessId,1,PointBillingService.TTS_CHARACTER,units);
    }

    @Override
    @Transactional
    public void finish(long accountId, String businessId, String outcome)
    {
        if (accountId <= 0 || businessId == null || !businessId.matches("[1-9][0-9]{0,18}:[0-9]{1,5}")
            || outcome == null || !java.util.Set.of("SETTLE", "REVIEW", "RELEASE").contains(outcome))
            throw new ServiceException("TTS 额度状态无效", 400);
        if (mapper.reservation(accountId,businessId) != null) finishLegacy(accountId,businessId,outcome);
        else billing.finish(accountId,"TTS_SEGMENT",businessId,outcome,-1);
    }

    private void finishLegacy(long accountId,String businessId,String outcome)
    {
        if (mapper.balanceForUpdate(accountId)==null) throw new ServiceException("TTS 额度余额不存在",404);
        Map<String,Object> row=mapper.reservationForUpdate(accountId,businessId);
        if (row==null) throw new ServiceException("TTS 额度预占不存在",404);
        String next=switch(outcome){case "SETTLE"->"SETTLED";case "RELEASE"->"RELEASED";default->"REVIEW_REQUIRED";};
        if(next.equals(row.get("state")))return;
        if(!"RESERVED".equals(row.get("state")))throw new ServiceException("TTS 额度终态冲突",409);
        long id=number(row.get("id")),units=number(row.get("reservedUnits"));
        if("REVIEW_REQUIRED".equals(next))
        {
            if(mapper.reviewReservation(id)!=1)throw new ServiceException("TTS 待核对状态已变化",409);
            return;
        }
        long used="SETTLED".equals(next)?units:0;
        if(mapper.finishReservation(id,next,used)!=1||mapper.finishBalance(accountId,units,used)!=1)
            throw new ServiceException("TTS 额度终态已变化",409);
        mapper.insertEntry(nextId(),accountId,id,"tts:"+businessId+":"+outcome.toLowerCase(),
            used>0?"SETTLE":"RELEASE",used,-units);
    }

    private long nextId()
    {
        Long id = mapper.nextId();
        if (id == null || id <= 0) throw new IllegalStateException("Could not allocate TTS quota identifier");
        return id;
    }
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
}
