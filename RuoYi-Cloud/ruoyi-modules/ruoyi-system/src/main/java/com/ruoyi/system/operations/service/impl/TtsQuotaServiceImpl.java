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

    @Override public Long findReservation(long accountId,String businessId)
    {
        if (accountId<=0 || businessId==null || !businessId.matches("[1-9][0-9]{0,18}:[0-9]{1,5}"))
            throw new ServiceException("TTS 额度请求无效",400);
        var legacy=mapper.reservation(accountId,businessId);
        return legacy==null?billing.findTtsReservation(accountId,businessId):legacy.id();
    }

    @Override
    @Transactional
    public long reserve(long accountId, long applicationId, String businessId, long units)
    {
        if (accountId <= 0 || applicationId <= 0 || businessId == null || !businessId.matches("[1-9][0-9]{0,18}:[0-9]{1,5}")
            || units <= 0 || units > 8000) throw new ServiceException("TTS 额度请求无效", 400);
        String purpose = mapper.applicationPurpose(accountId, applicationId);
        if ("VOICE_PREVIEW".equals(purpose)) return 0;
        if (!"USER".equals(purpose)) throw new ServiceException("TTS 应用不可用", 403);
        TtsQuotaMapper.Reservation legacy = mapper.reservation(accountId,businessId);
        if (legacy != null)
        {
            if (legacy.reservedUnits() != units || "RELEASED".equals(legacy.state()))
                throw new ServiceException("TTS 段额度请求冲突",409);
            return legacy.id();
        }
        long id = nextId();
        return billing.reserveTts(accountId,applicationId,id,businessId,units);
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
        TtsQuotaMapper.Reservation row=mapper.reservationForUpdate(accountId,businessId);
        if (row==null) throw new ServiceException("TTS 额度预占不存在",404);
        String next=switch(outcome){case "SETTLE"->"SETTLED";case "RELEASE"->"RELEASED";default->"REVIEW_REQUIRED";};
        if(next.equals(row.state()))return;
        if("REVIEW_REQUIRED".equals(next) && java.util.Set.of("SETTLED","RELEASED").contains(row.state())) return;
        if(!java.util.Set.of("RESERVED","REVIEW_REQUIRED").contains(row.state()))throw new ServiceException("TTS 额度终态冲突",409);
        long id=row.id(),units=row.reservedUnits();
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
}
