package com.ruoyi.system.asset.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.domain.OutboxEvent;
import com.ruoyi.system.asset.mapper.GenerationOutboxMapper;

/** Publishes committed generation Outbox events; only broker confirms settle delivery. */
@Component
public class GenerationOutboxPublisher
{
    private static final Logger LOG = LoggerFactory.getLogger(GenerationOutboxPublisher.class);

    private final GenerationOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final String publisherId;

    public GenerationOutboxPublisher(GenerationOutboxMapper outboxMapper, RabbitTemplate rabbitTemplate,
        TransactionTemplate transactions, ObjectMapper objectMapper,
        @Value("${platform.generation.outbox.publisher-id}") String publisherId)
    {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisherId = publisherId;
    }

    @Scheduled(fixedDelayString = "${platform.generation.outbox.publisher-delay-ms}")
    public void publishNext()
    {
        OutboxEvent event = claimNext();
        if (event == null) return;
        try
        {
            CorrelationData correlation = new CorrelationData(event.getEventId());
            rabbitTemplate.send(GenerationMessagingTopology.EVENTS_EXCHANGE, GenerationMessagingTopology.ROUTING_KEY,
                message(event), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new ServiceException("RabbitMQ 拒绝 Outbox 消息：" + confirm.getReason());
            settle(event.getId());
        }
        catch (Exception error)
        {
            release(event);
            LOG.warn("generation Outbox publish deferred: eventId={}, error={}", event.getEventId(), error.getClass().getSimpleName());
        }
    }

    private OutboxEvent claimNext()
    {
        return transactions.execute(status -> {
            OutboxEvent event = outboxMapper.selectPublishableForUpdate();
            if (event == null) return null;
            if (outboxMapper.claimForPublish(event.getId(), publisherId) != 1)
                throw new ServiceException("Outbox publisher lease lost");
            return event;
        });
    }

    private void settle(Long id)
    {
        transactions.executeWithoutResult(status -> {
            if (outboxMapper.markPublished(id, publisherId) != 1)
                throw new ServiceException("Outbox publisher lease lost");
        });
    }

    private void release(OutboxEvent event)
    {
        int priorAttempts = event.getAttemptCount() == null ? 0 : event.getAttemptCount();
        int delaySeconds = Math.min(300, 5 * (1 << Math.min(5, priorAttempts)));
        transactions.executeWithoutResult(status -> outboxMapper.releaseForRetry(event.getId(), publisherId, delaySeconds));
    }

    private Message message(OutboxEvent event) throws Exception
    {
        JsonNode payload = objectMapper.readTree(event.getPayload());
        if (!payload.isObject() || !payload.hasNonNull("taskId"))
            throw new ServiceException("Outbox 缺少 generation taskId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", event.getSchemaVersion());
        body.put("eventId", event.getEventId());
        body.put("eventType", event.getEventType());
        body.put("traceId", event.getTraceId());
        body.put("accountId", event.getAccountId().toString());
        body.put("payload", objectMapper.convertValue(payload, Map.class));
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.getEventId());
        properties.setHeader("x-trace-id", event.getTraceId());
        return new Message(objectMapper.writeValueAsBytes(body), properties);
    }
}
