package com.ruoyi.system.operations.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.operations.dto.PointRatePublishRequest;
import com.ruoyi.system.operations.mapper.PointBillingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointBillingService
{
    public static final String GENERATION_ACTION="GENERATION_ACTION", TTS_CHARACTER="TTS_CHARACTER";
    private final PointBillingMapper mapper;
    public PointBillingService(PointBillingMapper mapper) { this.mapper=mapper; }
    public Long findTtsReservation(long accountId,String businessId) { return mapper.ttsReservationId(accountId,businessId); }

    @Transactional
    public long reserve(long accountId,long reservationId,String businessType,String businessId,int reservationNo,
        String item,long measuredUnits)
    {
        return reserveLocked(accountId,reservationId,businessType,businessId,reservationNo,item,measuredUnits,null,null);
    }

    @Transactional
    public long reserveTts(long accountId,long applicationId,long reservationId,String businessId,long units)
    {
        if (applicationId<=0 || businessId==null || !businessId.matches("[1-9][0-9]{0,18}:[0-9]{1,5}"))
            throw new ServiceException("TTS 积分请求无效",400);
        return reserveLocked(accountId,reservationId,"TTS_SEGMENT",businessId,1,TTS_CHARACTER,units,
            businessId.substring(0,businessId.indexOf(':')),applicationId);
    }

    private long reserveLocked(long accountId,long reservationId,String businessType,String businessId,int reservationNo,
        String item,long measuredUnits,String requestKey,Long applicationId)
    {
        if (measuredUnits<=0) throw new ServiceException("积分计量无效",400);
        // Serialize the whole request's first price selection, including two concurrently starting segments.
        if(mapper.balanceForUpdate(accountId)==null)
            throw new ServiceException("积分余额不存在或不足",429);
        PointBillingMapper.TtsPrice fixed=requestKey==null?null:mapper.ttsPrice(accountId,requestKey);
        if (fixed!=null && fixed.applicationId()!=null && !fixed.applicationId().equals(applicationId))
            throw new ServiceException("TTS 请求归属冲突",409);
        Map<String,Object> old=mapper.reservation(accountId,businessType,businessId,reservationNo);
        if(old!=null)
        {
            if(number(old.get("measuredUnits"))!=measuredUnits || !item.equals(old.get("billingItem"))
                || "RELEASED".equals(old.get("state"))) throw new ServiceException("积分预占请求冲突",409);
            return number(old.get("id"));
        }
        Quote quote=fixed==null?quote(item,measuredUnits):new Quote(fixed.rateVersionId(),fixed.unitPriceCent(),
            Math.multiplyExact(measuredUnits,fixed.unitPriceCent()));
        if(mapper.reserveBalance(accountId,quote.amountCent())!=1)
            throw new ServiceException("积分不足，需要 "+points(quote.amountCent())+" 积分",429);
        mapper.insertReservation(reservationId,accountId,businessType,businessId,reservationNo,item,measuredUnits,
            quote.unitPriceCent(),quote.rateVersionId(),quote.amountCent(),requestKey,applicationId);
        entry(accountId,reservationId,businessType+":"+businessId+":reserve:"+reservationNo,"RESERVE",0,0,quote.amountCent(),null,null);
        return reservationId;
    }

    @Transactional
    public Map<String,Object> resume(long accountId,long reservationId,String businessType,String businessId)
    {
        if(mapper.balanceForUpdate(accountId)==null) throw new ServiceException("积分余额不存在",404);
        Map<String,Object> old=mapper.latestReservationForUpdate(accountId,businessType,businessId);
        if(old==null) throw new ServiceException("积分预占不存在",409);
        if(!"RELEASED".equals(old.get("state"))) return old;
        int no=((Number)old.get("reservationNo")).intValue()+1;
        long amount=number(old.get("reservedCent"));
        if(mapper.reserveBalance(accountId,amount)!=1) throw new ServiceException("积分不足，需要 "+points(amount)+" 积分",429);
        mapper.insertReservation(reservationId,accountId,businessType,businessId,no,String.valueOf(old.get("billingItem")),
            number(old.get("measuredUnits")),number(old.get("unitPriceCent")),number(old.get("rateVersionId")),amount,null,null);
        entry(accountId,reservationId,businessType+":"+businessId+":reserve:"+no,"RESERVE",0,0,amount,null,null);
        return mapper.reservation(accountId,businessType,businessId,no);
    }

    @Transactional
    public void finish(long accountId,String businessType,String businessId,String outcome,long successfulItems)
    {
        if (!List.of("SETTLE","RELEASE","REVIEW").contains(outcome)) throw new ServiceException("积分终态无效",400);
        if(mapper.balanceForUpdate(accountId)==null) throw new ServiceException("积分余额不存在",404);
        Map<String,Object> row=mapper.latestReservationForUpdate(accountId,businessType,businessId);
        if(row==null) throw new ServiceException("积分预占不存在",404);
        String state=String.valueOf(row.get("state"));
        if("REVIEW".equals(outcome))
        {
            if("REVIEW_REQUIRED".equals(state) || "SETTLED".equals(state) || "RELEASED".equals(state)) return;
            if(!"RESERVED".equals(state) || mapper.reviewReservation(number(row.get("id")))!=1)
                throw new ServiceException("积分待核对状态已变化",409);
            return;
        }
        String target="RELEASE".equals(outcome) || number(row.get("reservedCent"))==0 ?"RELEASED":"SETTLED";
        if(target.equals(state)) return;
        if(!"RESERVED".equals(state) && !"REVIEW_REQUIRED".equals(state)) throw new ServiceException("积分终态冲突",409);
        long reserved=number(row.get("reservedCent"));
        long settled="SETTLE".equals(outcome)
            ? successfulItems < 0 ? reserved : Math.min(reserved,Math.multiplyExact(successfulItems,number(row.get("unitPriceCent")))) : 0;
        if(settled==0) target="RELEASED";
        long id=number(row.get("id"));
        if(mapper.finishReservation(id,state,target,settled)!=1 || mapper.finishBalance(accountId,reserved,settled)!=1)
            throw new ServiceException("积分结算状态已变化",409);
        entry(accountId,id,businessType+":"+businessId+":"+target.toLowerCase()+":"+row.get("reservationNo"),
            settled>0?"SETTLE":"RELEASE",0,settled,-reserved,null,null);
    }

    @Transactional
    public boolean review(long operatorId,long reservationId,String decision,String reason)
    {
        Map<String,Object> pre=mapper.reservationById(reservationId);
        if(pre==null) return false;
        long accountId=number(pre.get("accountId"));
        if(mapper.balanceForUpdate(accountId)==null) throw new ServiceException("积分余额不存在",404);
        Map<String,Object> row=mapper.reservationByIdForUpdate(reservationId);
        if(row==null||number(row.get("accountId"))!=accountId) throw new ServiceException("积分预占已变化",409);
        String target="SETTLE".equals(decision)?"SETTLED":"RELEASED";
        if(target.equals(row.get("state"))) return true;
        if(!"REVIEW_REQUIRED".equals(row.get("state"))) throw new ServiceException("只有待核对积分可处理",409);
        long reserved=number(row.get("reservedCent")),settled="SETTLE".equals(decision)?reserved:0;
        if(mapper.finishReservation(reservationId,"REVIEW_REQUIRED",target,settled)!=1
            ||mapper.finishBalance(accountId,reserved,settled)!=1) throw new ServiceException("积分核对状态已变化",409);
        entry(accountId,reservationId,"review:"+reservationId,target.equals("SETTLED")?"SETTLE":"RELEASE",
            0,settled,-reserved,operatorId,reason);
        return true;
    }

    @Transactional
    public boolean grant(long operatorId,long accountId,BigDecimal pointAmount,String key,String reason)
    {
        if(operatorId<=0||accountId<=0||key==null||!key.matches("[\\x21-\\x7e]{1,64}")||reason==null||reason.isBlank()||reason.length()>500)
            throw new ServiceException("积分授予参数无效",400);
        if(mapper.activeAccountForUpdate(accountId)==null) throw new ServiceException("账号不存在或不可用",404);
        long amount=cent(pointAmount); if(amount<=0) throw new ServiceException("授予积分必须大于零",400);
        String eventKey="admin:point-grant:"+key;
        Map<String,Object> old=mapper.grantByKey(accountId,eventKey);
        if(old!=null)
        {
            if(number(old.get("amountCent"))!=amount || !reason.equals(old.get("reason")))
                throw new ServiceException("同一幂等键的授予参数不同",409);
            return false;
        }
        Map<String,Object> balance=mapper.balanceForUpdate(accountId);
        if(balance==null) mapper.insertBalance(accountId,amount);
        else if(mapper.addGranted(accountId,amount)!=1) throw new ServiceException("积分余额已变化",409);
        entry(accountId,null,eventKey,"GRANT",amount,0,0,operatorId,reason); return true;
    }

    public Map<String,Object> publishedRates()
    { return Map.of("current",display(mapper.currentRate()),"items",mapper.rates().stream().map(PointBillingService::display).toList()); }

    @Transactional public Map<String,Object> publish(long operatorId,PointRatePublishRequest input)
    {
        if(operatorId<=0||input==null) throw new ServiceException("积分费率参数无效",400);
        Map<String,Object> latest=mapper.latestRateForUpdate();
        long version=latest==null?1:Math.addExact(number(latest.get("versionNo")),1),id=nextId();
        LocalDateTime effective=input.effectiveAt()==null?LocalDateTime.now(ZoneOffset.UTC):input.effectiveAt();
        mapper.insertRate(id,version,cent(input.generationActionPoints()),cent(input.ttsCharacterPoints()),
            cent(input.storageBytePoints()),effective,operatorId);
        return display(Map.of("id",id,"versionNo",version,"generationActionCent",cent(input.generationActionPoints()),
            "ttsCharacterCent",cent(input.ttsCharacterPoints()),"storageByteCent",cent(input.storageBytePoints()),"effectiveAt",effective));
    }

    private Quote quote(String item,long units)
    {
        if(units<=0 || !List.of(GENERATION_ACTION,TTS_CHARACTER).contains(item)) throw new ServiceException("积分计量无效",400);
        Map<String,Object> rate=mapper.currentRate(); if(rate==null) throw new ServiceException("当前没有已生效的积分费率",503);
        long price=number(rate.get(GENERATION_ACTION.equals(item)?"generationActionCent":"ttsCharacterCent"));
        return new Quote(number(rate.get("id")),price,Math.multiplyExact(units,price));
    }
    private void entry(long accountId,Long reservationId,String key,String type,long granted,long used,long reserved,Long operator,String reason)
    { mapper.insertEntry(nextId(),accountId,reservationId,key,type,granted,used,reserved,operator,reason); }
    private long nextId(){Long id=mapper.nextId();if(id==null||id<=0)throw new IllegalStateException("无法生成积分标识");return id;}
    private static long cent(BigDecimal value){try{return value.movePointRight(2).longValueExact();}catch(Exception e){throw new ServiceException("积分最多保留两位小数",400);}}
    private static BigDecimal points(long value){return BigDecimal.valueOf(value,2);}
    private static long number(Object value){return ((Number)value).longValue();}
    private static Map<String,Object> display(Map<String,Object> source){if(source==null)return Map.of();Map<String,Object> r=new LinkedHashMap<>(source);
        for(String key:List.of("generationActionCent","ttsCharacterCent","storageByteCent"))if(r.get(key) instanceof Number n)r.put(key.replace("Cent","Points"),points(n.longValue()));
        r.remove("generationActionCent");r.remove("ttsCharacterCent");r.remove("storageByteCent");if(r.get("id")!=null)r.put("id",r.get("id").toString());return r;}
    private record Quote(long rateVersionId,long unitPriceCent,long amountCent){}
}
