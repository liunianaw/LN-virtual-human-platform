package com.ruoyi.session.runtime;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Official Beijing DashScope realtime TTS. The API key is read only from the process environment. */
@Component
public class OfficialDashScopeTtsRuntimeAdapter implements TtsRuntimeAdapter
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private final VoiceRuntimeProperties properties;
    private final TtsAdapterSupport support;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OfficialDashScopeTtsRuntimeAdapter(VoiceRuntimeProperties properties, TtsAdapterSupport support)
    {
        this.properties = properties;
        this.support = support;
    }

    @Override
    public TtsProviderKind providerKind()
    {
        return TtsProviderKind.OFFICIAL;
    }

    @Override
    public void submit(TtsSynthesisWork work, TtsCompletionSink completionSink)
    {
        CompletableFuture.runAsync(() -> synthesize(work, completionSink));
    }

    private void synthesize(TtsSynthesisWork work, TtsCompletionSink completionSink)
    {
        VoiceRuntimeProperties.Provider config = properties.getOfficial();
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (blank(apiKey))
        {
            support.fail(work, completionSink, "OFFICIAL_TTS_NOT_CONFIGURED");
            return;
        }
        try
        {
            Duration timeout = config.getTimeout();
            AudioListener listener = new AudioListener(work.text(), configuredVoice(work, config), properties.getMaxAudioBytes());
            WebSocket socket = client.newWebSocketBuilder().header("Authorization", "Bearer " + apiKey.trim())
                    .header("User-Agent", "LN-Session/1").buildAsync(endpoint(config), listener)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            try
            {
                byte[] audio = listener.await(timeout);
                support.complete(work, completionSink, audio);
            }
            finally
            {
                socket.sendText(event("session.finish"), true);
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "complete");
            }
        }
        catch (TimeoutException exception)
        {
            support.fail(work, completionSink, "OFFICIAL_TTS_TIMEOUT");
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            support.fail(work, completionSink, "OFFICIAL_TTS_INTERRUPTED");
        }
        catch (ExecutionException | IllegalArgumentException exception)
        {
            support.fail(work, completionSink, "OFFICIAL_TTS_FAILED");
        }
        catch (RuntimeException exception)
        {
            support.fail(work, completionSink, "OFFICIAL_TTS_FAILED");
        }
    }

    private URI endpoint(VoiceRuntimeProperties.Provider config)
    {
        if (blank(config.getEndpoint()) || blank(config.getModel()))
        {
            throw new IllegalArgumentException("Official TTS endpoint is not configured");
        }
        URI base = URI.create(config.getEndpoint());
        if (!"wss".equalsIgnoreCase(base.getScheme()) || blank(base.getHost()))
        {
            throw new IllegalArgumentException("Official TTS endpoint must use wss");
        }
        String separator = base.getQuery() == null ? "?" : "&";
        return URI.create(base + separator + "model=" + URLEncoder.encode(config.getModel(), StandardCharsets.UTF_8));
    }

    private static String configuredVoice(TtsSynthesisWork work, VoiceRuntimeProperties.Provider config)
    {
        String voice = work.voice().providerVoiceRef();
        if (blank(voice))
        {
            voice = config.getVoice();
        }
        if (blank(voice) || voice.length() > 128)
        {
            throw new IllegalArgumentException("Official TTS voice is not configured");
        }
        return voice;
    }

    private static String event(String type)
    {
        return event(type, null);
    }

    private static String event(String type, Object payload)
    {
        try
        {
            var root = JSON.createObjectNode().put("event_id", UUID.randomUUID().toString()).put("type", type);
            if (payload != null)
            {
                root.setAll((com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(payload));
            }
            return JSON.writeValueAsString(root);
        }
        catch (Exception exception)
        {
            throw new IllegalStateException("Could not encode TTS provider event", exception);
        }
    }

    private static boolean blank(String value)
    {
        return value == null || value.isBlank();
    }

    private static final class AudioListener implements WebSocket.Listener
    {
        private final String text;
        private final String voice;
        private final long maxAudioBytes;
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private final StringBuilder frames = new StringBuilder();
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private volatile WebSocket socket;

        private AudioListener(String text, String voice, long maxAudioBytes)
        {
            this.text = text;
            this.voice = voice;
            this.maxAudioBytes = maxAudioBytes;
        }

        @Override
        public void onOpen(WebSocket webSocket)
        {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            frames.append(data);
            if (last)
            {
                try
                {
                    JsonNode message = JSON.readTree(frames.toString());
                    frames.setLength(0);
                    handle(message);
                }
                catch (Exception exception)
                {
                    completed.completeExceptionally(exception);
                    webSocket.abort();
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        private void handle(JsonNode message)
        {
            String type = message.path("type").asText();
            if ("session.created".equals(type))
            {
                socket.sendText(event("session.update", java.util.Map.of("session", java.util.Map.of("voice", voice, "mode", "commit",
                        "language_type", "Chinese", "response_format", "wav", "sample_rate", 24000))), true);
            }
            else if ("session.updated".equals(type))
            {
                socket.sendText(event("input_text_buffer.append", java.util.Map.of("text", text)), true);
                socket.sendText(event("input_text_buffer.commit"), true);
            }
            else if ("response.audio.delta".equals(type))
            {
                JsonNode delta = message.get("delta");
                if (delta == null || !delta.isTextual())
                {
                    throw new IllegalArgumentException("Provider audio delta is invalid");
                }
                byte[] bytes = Base64.getDecoder().decode(delta.asText());
                if (audio.size() + bytes.length > maxAudioBytes)
                {
                    throw new IllegalArgumentException("Provider audio exceeds the configured maximum");
                }
                audio.writeBytes(bytes);
            }
            else if ("response.audio.done".equals(type))
            {
                completed.complete(audio.toByteArray());
            }
            else if ("error".equals(type) || ("response.done".equals(type) && !"completed".equals(message.path("response").path("status").asText())))
            {
                completed.completeExceptionally(new IllegalArgumentException("Provider rejected the TTS request"));
            }
        }

        private byte[] await(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException
        {
            return completed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
