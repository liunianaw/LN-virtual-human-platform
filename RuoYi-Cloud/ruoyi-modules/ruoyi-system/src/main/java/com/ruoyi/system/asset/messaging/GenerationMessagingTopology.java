package com.ruoyi.system.asset.messaging;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable topology for generation wake-up messages; task state stays in system. */
@Configuration
public class GenerationMessagingTopology
{
    public static final String EVENTS_EXCHANGE = "ln.platform.events";
    public static final String RETRY_EXCHANGE = "ln.platform.retry";
    public static final String DEAD_LETTER_EXCHANGE = "ln.platform.dlx";
    public static final String ROUTING_KEY = "avatar.generation.requested.v1";
    public static final String RETRY_ROUTING_KEY = "avatar.generation.requested.retry.10s.v1";
    public static final String DEAD_LETTER_ROUTING_KEY = "avatar.generation.requested.dlq.v1";
    public static final String QUEUE = "ln.media.avatar-generation.v1";
    public static final String RETRY_QUEUE = "ln.media.avatar-generation.retry.10s.v1";
    public static final String DEAD_LETTER_QUEUE = "ln.media.avatar-generation.dlq.v1";

    @Bean
    Declarables generationMessagingDeclarables()
    {
        DirectExchange events = new DirectExchange(EVENTS_EXCHANGE, true, false);
        DirectExchange retry = new DirectExchange(RETRY_EXCHANGE, true, false);
        DirectExchange deadLetter = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue primary = QueueBuilder.durable(QUEUE)
            .withArguments(Map.of("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY)).build();
        Queue retryQueue = QueueBuilder.durable(RETRY_QUEUE)
            .withArguments(Map.of("x-message-ttl", 10_000, "x-dead-letter-exchange", EVENTS_EXCHANGE,
                "x-dead-letter-routing-key", ROUTING_KEY)).build();
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        return new Declarables(events, retry, deadLetter, primary, retryQueue, deadLetterQueue,
            BindingBuilder.bind(primary).to(events).with(ROUTING_KEY),
            BindingBuilder.bind(retryQueue).to(retry).with(RETRY_ROUTING_KEY),
            BindingBuilder.bind(deadLetterQueue).to(deadLetter).with(DEAD_LETTER_ROUTING_KEY));
    }
}
