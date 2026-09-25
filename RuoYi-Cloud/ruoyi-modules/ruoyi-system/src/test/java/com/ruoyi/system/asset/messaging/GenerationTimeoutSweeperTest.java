package com.ruoyi.system.asset.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.asset.mapper.GenerationWorkerMapper;
import com.ruoyi.system.asset.service.IGenerationQuotaService;

class GenerationTimeoutSweeperTest
{
    @Test
    void expiresOnlyUnsubmittedStepsBeforeFinishingTheirTasks()
    {
        GenerationWorkerMapper mapper = mock(GenerationWorkerMapper.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(mapper.expireStalledUnsubmittedSteps(300)).thenReturn(8);
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        IGenerationQuotaService quota = mock(IGenerationQuotaService.class);
        new GenerationTimeoutSweeper(mapper, transactions, quota, 300).sweep();

        InOrder order = inOrder(mapper);
        order.verify(mapper).expireStalledUnsubmittedSteps(300);
        order.verify(mapper).markTimedOutTasksFailed();
        org.mockito.Mockito.verify(quota).finishTimedOutTasks();
    }
}
