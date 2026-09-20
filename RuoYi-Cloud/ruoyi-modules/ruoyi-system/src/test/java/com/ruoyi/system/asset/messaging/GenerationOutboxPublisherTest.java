package com.ruoyi.system.asset.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.asset.domain.OutboxEvent;
import com.ruoyi.system.asset.mapper.GenerationOutboxMapper;

class GenerationOutboxPublisherTest
{
    @Test
    void marksEventSentOnlyAfterBrokerConfirm() throws Exception
    {
        GenerationOutboxMapper mapper = mock(GenerationOutboxMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TransactionTemplate transactions = transactions();
        OutboxEvent event = event(0);
        when(mapper.selectPublishableForUpdate()).thenReturn(event);
        when(mapper.claimForPublish(101L, "test-publisher")).thenReturn(1);
        when(mapper.markPublished(101L, "test-publisher")).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher(mapper, rabbitTemplate, transactions).publishNext();

        org.mockito.ArgumentCaptor<Message> message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), message.capture(), any(CorrelationData.class));
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getValue().getMessageProperties().getDeliveryMode());
        assertEquals(12L, new ObjectMapper().readTree(message.getValue().getBody()).path("payload").path("taskId").asLong());
        verify(mapper).markPublished(101L, "test-publisher");
    }

    @Test
    void returnsRejectedEventToPendingWithBackoff() throws Exception
    {
        GenerationOutboxMapper mapper = mock(GenerationOutboxMapper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OutboxEvent event = event(2);
        when(mapper.selectPublishableForUpdate()).thenReturn(event);
        when(mapper.claimForPublish(101L, "test-publisher")).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "not-routable"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher(mapper, rabbitTemplate, transactions()).publishNext();

        verify(mapper).releaseForRetry(101L, "test-publisher", 20);
    }

    private static GenerationOutboxPublisher publisher(GenerationOutboxMapper mapper, RabbitTemplate rabbitTemplate,
        TransactionTemplate transactions)
    {
        return new GenerationOutboxPublisher(mapper, rabbitTemplate, transactions, new ObjectMapper(), "test-publisher");
    }

    private static TransactionTemplate transactions()
    {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        return transactions;
    }

    private static OutboxEvent event(int attemptCount)
    {
        OutboxEvent event = new OutboxEvent();
        event.setId(101L);
        event.setAccountId(7L);
        event.setEventId("event-101");
        event.setEventType("AVATAR_GENERATION_REQUESTED");
        event.setSchemaVersion(1);
        event.setTraceId("trace-101");
        event.setPayload("{\"taskId\":12}");
        event.setAttemptCount(attemptCount);
        return event;
    }
}
