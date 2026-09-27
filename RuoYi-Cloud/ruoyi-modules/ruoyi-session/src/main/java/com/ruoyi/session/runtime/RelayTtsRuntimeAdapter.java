package com.ruoyi.session.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Local development Relay adapter. Its dedicated bearer is never accepted from a browser request. */
@Component
public class RelayTtsRuntimeAdapter implements TtsRuntimeAdapter
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private final VoiceRuntimeProperties properties;
    private final TtsAdapterSupport support;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public RelayTtsRuntimeAdapter(VoiceRuntimeProperties properties, TtsAdapterSupport support)
    {
        this.properties = properties;
        this.support = support;
    }

    @Override
    public TtsProviderKind providerKind()
    {
        return TtsProviderKind.RELAY;
    }

    @Override
    public void submit(TtsSynthesisWork work, TtsCompletionSink completionSink)
    {
        CompletableFuture.runAsync(() -> synthesize(work, completionSink));
    }

    private void synthesize(TtsSynthesisWork work, TtsCompletionSink completionSink)
    {
        String accessToken = System.getenv("LN_RELAY_ACCESS_TOKEN");
        VoiceRuntimeProperties.Provider config = properties.getRelay();
        if (blank(accessToken))
        {
            support.fail(work, completionSink, "RELAY_TTS_NOT_CONFIGURED");
            return;
        }
        try
        {
            Duration timeout = config.getTimeout();
            String requestId = "tts-" + work.turnId() + '-' + work.ordinal() + '-' + work.generation() + '-' + UUID.randomUUID();
            HttpRequest request = HttpRequest.newBuilder(audioSpeechEndpoint(config)).timeout(timeout)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken.trim()).header("X-LN-Protocol-Version", "1")
                    .header("X-Request-Id", requestId).header(HttpHeaders.ACCEPT, MediaType.valueOf("audio/wav").toString())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody(work, requestId, config)))).build();
            completionSink.beforeExternal(work);
            HttpResponse<byte[]> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String returnedRequestId = response.headers().firstValue("X-Request-Id").orElse("");
            String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("");
            if (response.statusCode() != 200 || !requestId.equals(returnedRequestId) || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("audio/wav"))
            {
                support.fail(work, completionSink, "RELAY_TTS_FAILED");
                return;
            }
            support.complete(work, completionSink, response.body());
        }
        catch (TimeoutException exception)
        {
            support.fail(work, completionSink, "RELAY_TTS_TIMEOUT");
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            support.fail(work, completionSink, "RELAY_TTS_INTERRUPTED");
        }
        catch (ExecutionException | IllegalArgumentException exception)
        {
            support.fail(work, completionSink, "RELAY_TTS_FAILED");
        }
        catch (Exception exception)
        {
            support.fail(work, completionSink, "RELAY_TTS_FAILED");
        }
    }

    private static URI audioSpeechEndpoint(VoiceRuntimeProperties.Provider config)
    {
        if (blank(config.getEndpoint()))
        {
            throw new IllegalArgumentException("Relay TTS endpoint is not configured");
        }
        URI base = URI.create(config.getEndpoint());
        if (!"http".equalsIgnoreCase(base.getScheme()) && !"https".equalsIgnoreCase(base.getScheme()))
        {
            throw new IllegalArgumentException("Relay TTS endpoint must use HTTP(S)");
        }
        String value = base.toString();
        return URI.create(value.endsWith("/") ? value + "audio/speech" : value + "/audio/speech");
    }

    private static Map<String, String> requestBody(TtsSynthesisWork work, String requestId, VoiceRuntimeProperties.Provider config)
    {
        String voiceAlias = blank(work.voice().providerVoiceRef()) ? config.getVoice() : work.voice().providerVoiceRef();
        if (blank(voiceAlias) || blank(config.getModel()))
        {
            throw new IllegalArgumentException("Relay TTS Voice configuration is incomplete");
        }
        return Map.of("requestId", requestId, "applicationId", Long.toString(work.principal().applicationId()), "sessionId",
                Long.toString(work.principal().sessionId()), "turnId", work.turnId(), "segmentId", work.segmentId(), "text", work.text(),
                "voiceAlias", voiceAlias, "modelAlias", config.getModel());
    }

    private static boolean blank(String value)
    {
        return value == null || value.isBlank();
    }
}
