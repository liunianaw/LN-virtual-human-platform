package com.ruoyi.system.asset.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.domain.GenerationTask;
import com.ruoyi.system.asset.dto.CreateGenerationTaskRequest;
import com.ruoyi.system.asset.mapper.AssetMapper;

class AssetServiceTest
{
    @Test
    void reusesExistingTaskForTheSameAccountRequestId()
    {
        AssetMapper mapper = mock(AssetMapper.class);
        GenerationTask task = new GenerationTask();
        task.setId(101L);
        task.setAccountId(7L);
        task.setRequestId("request-1");
        task.setStatus("QUEUED");
        task.setInternalState("READY");
        task.setProgress(0);
        when(mapper.selectTaskByAccountAndRequest(7L, "request-1")).thenReturn(task);

        var response = service(mapper).createGenerationTask(7L, request());

        assertEquals(101L, response.getTaskId());
        verify(mapper).selectTaskByAccountAndRequest(7L, "request-1");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void rejectsAnotherAccountsReferenceFile()
    {
        AssetMapper mapper = mock(AssetMapper.class);

        ServiceException error = assertThrows(ServiceException.class, () -> service(mapper).readReference(8L, 101L));

        assertEquals(403, error.getCode());
    }

    private static AssetService service(AssetMapper mapper)
    {
        return new AssetService(mapper, new StaticListableBeanFactory().getBeanProvider(com.ruoyi.system.storage.ObjectStorage.class),
            new TransactionTemplate(), new ObjectMapper());
    }

    private static CreateGenerationTaskRequest request()
    {
        CreateGenerationTaskRequest request = new CreateGenerationTaskRequest();
        request.setSourceFileId(11L);
        request.setOfficialServiceId(12L);
        request.setRequestId("request-1");
        request.setName("演示形象");
        return request;
    }
}
