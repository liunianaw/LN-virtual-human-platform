package com.ruoyi.system.developer.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Closes a Session after a platform-side Application or asset is disabled. */
@Component
public class SessionLifecycleClient
{
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;

    public SessionLifecycleClient(ObjectMapper json) { this.json = json; }

    public void close(long accountId, long sessionId)
    {
        String bearer = System.getenv("LN_SYSTEM_TO_SESSION_INTERNAL_BEARER");
        String baseUrl = System.getenv("LN_SYSTEM_TO_SESSION_URL");
        if (bearer == null || bearer.isBlank() || baseUrl == null || baseUrl.isBlank())
            throw new ServiceException("会话内部身份未配置", HttpStatus.SERVICE_UNAVAILABLE.value());
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/sessions/" + sessionId + "/close"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new CloseBody(accountId)))).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2)
                throw new ServiceException("会话服务拒绝关闭请求", HttpStatus.BAD_GATEWAY.value());
        }
        catch (InterruptedException error)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("会话服务调用被中断", HttpStatus.SERVICE_UNAVAILABLE.value());
        }
        catch (IOException | IllegalArgumentException error)
        { throw new ServiceException("会话服务不可用", HttpStatus.SERVICE_UNAVAILABLE.value()); }
    }

    private record CloseBody(long accountId) { }
}
