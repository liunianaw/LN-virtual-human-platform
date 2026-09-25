package com.ruoyi.system.asset.service.impl;

import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.IGenerationQuotaService;

/** One task reservation covers its eight actions; all callers hold a local database transaction. */
@Service
public class GenerationQuotaServiceImpl implements IGenerationQuotaService
{
    private final AssetMapper mapper;

    public GenerationQuotaServiceImpl(AssetMapper mapper) { this.mapper = mapper; }

    @Override
    public void reserve(long accountId, long taskId, long reservationId)
    {
        admit(accountId);
        reserveBalance(accountId);
        mapper.insertQuotaReservation(reservationId, accountId, taskId, 1);
        mapper.insertQuotaEntry(nextId(), accountId, reservationId, event(taskId, "reserve", 1), "RESERVE", 0, 1);
    }

    @Override
    public void resume(long accountId, long taskId)
    {
        Map<String, Object> row = mapper.taskReservationForUpdate(accountId, taskId);
        if (row == null) throw new ServiceException("制作任务额度事实不存在", 409);
        String state = String.valueOf(row.get("state"));
        if ("RESERVED".equals(state) || "REVIEW_REQUIRED".equals(state)) { admit(accountId); return; }
        if (!"RELEASED".equals(state)) throw new ServiceException("制作任务额度状态不允许恢复", 409);
        int nextNo = ((Number) row.get("reservationNo")).intValue() + 1;
        admit(accountId);
        reserveBalance(accountId);
        long reservationId = nextId();
        mapper.insertQuotaReservation(reservationId, accountId, taskId, nextNo);
        mapper.insertQuotaEntry(nextId(), accountId, reservationId, event(taskId, "reserve", nextNo), "RESERVE", 0, 1);
        if (mapper.changeTaskReservation(accountId, taskId, ((Number) row.get("reservationId")).longValue(),
            reservationId, nextNo) != 1) throw new ServiceException("制作任务额度已变化", 409);
    }

    @Override
    public void finish(long accountId, long taskId)
    {
        Map<String, Object> row = mapper.taskReservationForUpdate(accountId, taskId);
        if (row == null) throw new ServiceException("制作任务额度事实不存在", 409);
        if (((Number) row.get("reservedUnits")).longValue() != 1) return; // historical zero-unit tasks
        String state = String.valueOf(row.get("state"));
        if (!"RESERVED".equals(state) && !"REVIEW_REQUIRED".equals(state)) return;
        String taskStatus = String.valueOf(row.get("taskStatus"));
        long reservationId = ((Number) row.get("reservationId")).longValue();
        int no = ((Number) row.get("reservationNo")).intValue();
        if ("SUCCEEDED".equals(taskStatus))
        {
            if (mapper.settleQuotaReservation(reservationId) != 1 || mapper.settleAvatarQuota(accountId) != 1)
                throw new ServiceException("制作任务额度结算失败", 409);
            mapper.insertQuotaEntry(nextId(), accountId, reservationId, event(taskId, "settle", no), "SETTLE", 1, -1);
        }
        else if ("FAILED".equals(taskStatus))
        {
            if (number(row.get("unknownAttempts")) > 0)
            {
                if ("RESERVED".equals(state) && mapper.reviewQuotaReservation(reservationId) != 1)
                    throw new ServiceException("制作任务额度待核对状态已变化", 409);
                return;
            }
            if (mapper.releaseQuotaReservation(reservationId) != 1 || mapper.releaseAvatarQuota(accountId) != 1)
                throw new ServiceException("制作任务额度释放失败", 409);
            mapper.insertQuotaEntry(nextId(), accountId, reservationId, event(taskId, "release", no), "RELEASE", 0, -1);
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

    private void reserveBalance(long accountId)
    {
        if (mapper.reserveAvatarQuota(accountId) == 1) return;
        Long remaining = mapper.availableAvatarQuota(accountId);
        throw new ServiceException("Avatar 生成额度不足，剩余 " + (remaining == null ? 0 : remaining) + " 次", 429);
    }

    private long nextId()
    {
        Long id = mapper.nextId();
        if (id == null || id <= 0) throw new IllegalStateException("无法生成额度流水标识");
        return id;
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static String event(long taskId, String action, int no)
    { return "generation:" + taskId + ":" + action + ":" + no; }
}
