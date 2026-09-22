package com.ruoyi.system.asset.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.asset.mapper.GenerationWorkerMapper;

/** Stops generation tasks that never reached a billable provider submission. */
@Component
public class GenerationTimeoutSweeper
{
    private static final Logger LOG = LoggerFactory.getLogger(GenerationTimeoutSweeper.class);

    private final GenerationWorkerMapper workerMapper;
    private final TransactionTemplate transactions;
    private final int timeoutSeconds;

    public GenerationTimeoutSweeper(GenerationWorkerMapper workerMapper, TransactionTemplate transactions,
        @Value("${platform.generation.timeout.unsubmitted-seconds}") int timeoutSeconds)
    {
        this.workerMapper = workerMapper;
        this.transactions = transactions;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${platform.generation.timeout.scan-delay-ms}")
    public void sweep()
    {
        transactions.executeWithoutResult(status -> {
            int steps = workerMapper.expireStalledUnsubmittedSteps(timeoutSeconds);
            int tasks = workerMapper.markTimedOutTasksFailed();
            if (steps > 0 || tasks > 0)
                LOG.warn("stopped stalled generation work before provider submission: steps={}, tasks={}", steps, tasks);
        });
    }
}
