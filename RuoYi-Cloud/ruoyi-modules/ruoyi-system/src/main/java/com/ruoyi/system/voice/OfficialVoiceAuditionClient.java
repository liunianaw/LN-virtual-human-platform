package com.ruoyi.system.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Service-to-service transport. No browser Token, provider credential or temporary file. */
@Component
public class OfficialVoiceAuditionClient
{
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public OfficialVoiceAuditionClient(ObjectMapper json) { this.json = json; }
    public byte[] audition(long versionId, long administratorId, String auditionKey, String text)
    {
        String base = System.getenv("LN_SYSTEM_TO_SESSION_URL");
        String bearer = System.getenv("LN_SYSTEM_TO_SESSION_INTERNAL_BEARER");
        if (base == null || base.isBlank() || bearer == null || bearer.isBlank())
            throw new ServiceException("试听服务未配置", 503);
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/official-voice-auditions"))
                .timeout(Duration.ofSeconds(65)).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(new Audition(versionId,administratorId,auditionKey,text)))).build();
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body())
            {
                byte[] audio = stream.readNBytes(1048577);
                if (response.statusCode() != 200 || audio.length == 0 || audio.length > 1048576)
                    throw new ServiceException("官方声音试听失败", 502);
                return audio;
            }
        }
        catch (ServiceException error) { throw error; }
        catch (InterruptedException error)
        { Thread.currentThread().interrupt(); throw new ServiceException("官方声音试听中断", 503); }
        catch (Exception error) { throw new ServiceException("官方声音试听不可用", 503); }
    }
    private record Audition(long voiceVersionId,long administratorId,String auditionKey,String text) { }
}
