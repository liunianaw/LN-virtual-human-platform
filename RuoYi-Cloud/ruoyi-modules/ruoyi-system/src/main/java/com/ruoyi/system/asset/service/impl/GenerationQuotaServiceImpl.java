package com.ruoyi.system.asset.service.impl;

import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.IGenerationQuotaService;
import com.ruoyi.system.operations.service.PointBillingService;

/** One task reservation covers its eight actions; all callers hold a local database transaction. */
@Service
public class GenerationQuotaServiceImpl implements IGenerationQuotaService
{
    private final AssetMapper mapper;
    private final PointBillingService billing;

    public GenerationQuotaServiceImpl(AssetMapper mapper, PointBillingService billing)
    { this.mapper = mapper; this.billing = billing; }

    @Override
    public void reserve(long accountId, long taskId, long reservationId, int actionCount)
    {
        if (actionCount <= 0 || actionCount > 100) throw new ServiceException("制作动作数量无效", 400);
        admit(accountId);
        billing.reserve(accountId, reservationId, "GENERATION", Long.toString(taskId), 1,
            PointBillingService.GENERATION_ACTION, actionCount);
    }

    @Override
    public void resume(long accountId, long taskId)
    {
        Map<String, Object> row = mapper.taskReservation(accountId, taskId);
        if (row == null) throw new ServiceException("制作任务额度事实不存在", 409);
        String state = String.valueOf(row.get("state"));
        if ("RESERVED".equals(state) || "REVIEW_REQUIRED".equals(state)) { admit(accountId); return; }
        if (!"RELEASED".equals(state)) throw new ServiceException("制作任务额度状态不允许恢复", 409);
        admit(accountId);
        long reservationId = nextId();
        if ("LEGACY".equals(row.get("ledgerType")))
        {
            int nextNo = ((Number) row.get("reservationNo")).intValue() + 1;
            reserveLegacy(accountId);
            mapper.insertQuotaReservation(reservationId, accountId, taskId, nextNo);
            mapper.insertQuotaEntry(nextId(), accountId, reservationId, event(taskId,"reserve",nextNo),"RESERVE",0,1);
            if (mapper.changeTaskReservation(accountId,taskId,number(row.get("reservationId")),reservationId,nextNo)!=1)
                throw new ServiceException("制作任务额度已变化",409);
            return;
        }
        Map<String,Object> next = billing.resume(accountId, reservationId, "GENERATION", Long.toString(taskId));
        if (mapper.changeTaskReservation(accountId, taskId, ((Number) row.get("reservationId")).longValue(),
            reservationId, ((Number) next.get("reservationNo")).intValue()) != 1)
            throw new ServiceException("制作任务积分已变化", 409);
    }

    @Override
    public void finish(long accountId, long taskId)
    {
        Map<String, Object> row = mapper.taskReservation(accountId, taskId);
        if (row == null) throw new ServiceException("制作任务额度事实不存在", 409);
        String state = String.valueOf(row.get("state"));
        if (!"RESERVED".equals(state) && !"REVIEW_REQUIRED".equals(state)) return;
        String taskStatus = String.valueOf(row.get("taskStatus"));
        if ("LEGACY".equals(row.get("ledgerType")))
        {
            finishLegacy(accountId,taskId,row,taskStatus,state);
            return;
        }
        if ("SUCCEEDED".equals(taskStatus))
            billing.finish(accountId, "GENERATION", Long.toString(taskId), "SETTLE", number(row.get("measuredUnits")));
        else if ("FAILED".equals(taskStatus))
        {
            if (number(row.get("unknownAttempts")) > 0)
            {
                billing.finish(accountId, "GENERATION", Long.toString(taskId), "REVIEW", 0);
                return;
            }
            long succeeded = number(row.get("successfulAttempts"));
            billing.finish(accountId, "GENERATION", Long.toString(taskId), succeeded > 0 ? "SETTLE" : "RELEASE", succeeded);
        }
    }

    @Override
    public void finishTimedOutTasks()
    {
        for (Map<String, Object> task : mapper.timedOutTasksForQuota())
            finish(((Number) task.get("accountId")).longValue(), ((Number) task.get("taskId")).longValue());
    }

    private void admit(long accountId)
    {
        Integer maximum = mapper.maxGenerationTasksForUpdate(accountId);
        if (maximum == null || mapper.countActiveGenerationTasks(accountId) >= maximum)
            throw new ServiceException("生成任务并发上限已达到或未配置", 429);
    }

    private void reserveLegacy(long accountId)
    {
        if(mapper.reserveAvatarQuota(accountId)==1)return;
        Long remaining=mapper.availableAvatarQuota(accountId);
        throw new ServiceException("Avatar 生成额度不足，剩余 "+(remaining==null?0:remaining)+" 次",429);
    }

    private void finishLegacy(long accountId,long taskId,Map<String,Object> row,String taskStatus,String state)
    {
        long reservationId=number(row.get("reservationId"));int no=((Number)row.get("reservationNo")).intValue();
        if("SUCCEEDED".equals(taskStatus))
        {
            if(mapper.settleQuotaReservation(reservationId)!=1||mapper.settleAvatarQuota(accountId)!=1)
                throw new ServiceException("制作任务额度结算失败",409);
            mapper.insertQuotaEntry(nextId(),accountId,reservationId,event(taskId,"settle",no),"SETTLE",1,-1);
        }
        else if("FAILED".equals(taskStatus))
        {
            if(number(row.get("unknownAttempts"))>0)
            {
                if("RESERVED".equals(state)&&mapper.reviewQuotaReservation(reservationId)!=1)
                    throw new ServiceException("制作任务额度待核对状态已变化",409);
                return;
            }
            if(mapper.releaseQuotaReservation(reservationId)!=1||mapper.releaseAvatarQuota(accountId)!=1)
                throw new ServiceException("制作任务额度释放失败",409);
            mapper.insertQuotaEntry(nextId(),accountId,reservationId,event(taskId,"release",no),"RELEASE",0,-1);
        }
    }

    private long nextId()
    {
        Long id = mapper.nextId();
        if (id == null || id <= 0) throw new IllegalStateException("无法生成额度流水标识");
        return id;
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static String event(long taskId,String action,int no){return "generation:"+taskId+":"+action+":"+no;}
}
