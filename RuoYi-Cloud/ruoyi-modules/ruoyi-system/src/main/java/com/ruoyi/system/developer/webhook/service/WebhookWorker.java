package com.ruoyi.system.developer.webhook.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.system.developer.webhook.service.impl.WebhookServiceImpl;

@Component
public class WebhookWorker
{
    private static final Logger LOG = LoggerFactory.getLogger(WebhookWorker.class);
    private final WebhookServiceImpl service;
    public WebhookWorker(WebhookServiceImpl service) { this.service = service; }

    @Scheduled(fixedDelay = 5000)
    public void run()
    {
        try
        {
            for (int i = 0; i < 20 && service.materializeNext(); i++) { }
            for (int i = 0; i < 10 && service.deliverNext(); i++) { }
        }
        catch (Exception error)
        { LOG.warn("Webhook worker deferred: {}", error.getClass().getSimpleName()); }
    }
}
