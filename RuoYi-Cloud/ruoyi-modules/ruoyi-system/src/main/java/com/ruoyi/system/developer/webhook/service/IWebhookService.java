package com.ruoyi.system.developer.webhook.service;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public interface IWebhookService
{
    record CreateInput(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 2048) String url,
        @NotEmpty List<String> events, Integer timeoutMs, Integer maxAttempts) { }
    record StatusInput(@NotBlank String status) { }

    Map<String, Object> list(long accountId, int pageNum, int pageSize);
    Map<String, Object> create(long accountId, CreateInput input, String key);
    Map<String, Object> rotate(long accountId, long endpointId, String ifMatch, String key);
    Map<String, Object> status(long accountId, long endpointId, StatusInput input, String ifMatch, String key);
    Map<String, Object> deliveries(long accountId, long endpointId, String from, String to, int pageNum, int pageSize);
    Map<String, Object> attempts(long accountId, long deliveryId, String from, String to, int pageNum, int pageSize);
    void requireActive(long accountId, long endpointId);
    void recordTerminal(long accountId, long taskId);
    void recordMissingTerminalEvents();
}
