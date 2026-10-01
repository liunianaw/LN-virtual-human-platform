package com.ruoyi.system.asset.service;

public interface IGenerationQuotaService
{
    /** Called inside the task creation transaction, before any payable work is published. */
    void reserve(long accountId, long taskId, long reservationId, int actionCount);

    /** Reopen a failed task only after restoring an already released reservation. */
    void resume(long accountId, long taskId);

    /** Settle a terminal task in the same transaction as its terminal status. */
    void finish(long accountId, long taskId);

    /** Complete timeouts written by the batch sweeper. */
    void finishTimedOutTasks();
}
