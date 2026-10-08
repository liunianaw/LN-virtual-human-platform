package com.ruoyi.session.runtime;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Official Beijing DashScope realtime TTS; credentials come from the protected System resolver. */
@Component
public class OfficialDashScopeTtsRuntimeAdapter implements TtsRuntimeAdapter
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(OfficialDashScopeTtsRuntimeAdapter.class);
    private final VoiceRuntimeProperties properties;
    private final TtsAdapterSupport support;
    private final OfficialServiceResolver resolver;
    private final VoiceExecutionPool execution;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OfficialDashScopeTtsRuntimeAdapter(VoiceRuntimeProperties properties, TtsAdapterSupport support, OfficialServiceResolver resolver,
        VoiceExecutionPool execution)
    {
        this.properties = properties; this.support = support; this.resolver = resolver;
        this.execution = execution;
    }

    @Override
    public TtsProviderKind providerKind()
    {
        return TtsProviderKind.OFFICIAL;
    }

    @Override
    public void submit(TtsSynthesisWork work, TtsCompletionSink completionSink)
    {
        execution.execute(() -> synthesize(work, completionSink), code -> support.fail(work, completionSink, code));
    }

    private void synthesize(TtsSynthesisWork work, TtsCompletionSink completionSink)
    {
        String stage = "resolve";
        AudioListener listener = null;
        try
        {
            byte[] audio = synthesizeAudio(work.voice(), work.text(), properties.getMaxAudioBytes(),
                () -> completionSink.beforeExternal(work));
            stage = "store";
            support.complete(work, completionSink, audio);
        }
        catch (TimeoutException exception)
        {
            LOG.warn("Official TTS timed out at stage={} providerStage={}", stage, listener == null ? "none" : listener.stage());
            support.fail(work, completionSink, "OFFICIAL_TTS_TIMEOUT");
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            support.fail(work, completionSink, "OFFICIAL_TTS_INTERRUPTED");
        }
        catch (ExecutionException | IllegalArgumentException exception)
        {
            LOG.warn("Official TTS failed at stage={} providerStage={} cause={}", stage,
                    listener == null ? "none" : listener.stage(), safeCause(exception));
            support.fail(work, completionSink, "OFFICIAL_TTS_FAILED");
        }
        catch (RuntimeException exception)
        {
            LOG.warn("Official TTS failed at stage={} providerStage={} cause={}", stage,
                    listener == null ? "none" : listener.stage(), safeCause(exception));
            support.fail(work, completionSink, exception instanceof RuntimeProblem problem ? problem.code() : "OFFICIAL_TTS_FAILED");
        }
    }

    /** Bounded administrator audition reuses the same provider protocol without a business Session. */
    @Override
    public byte[] audition(VoiceRuntimeBinding voice, String text, Runnable beforeExternal)
        throws InterruptedException, ExecutionException, TimeoutException
    {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 200)
            throw new IllegalArgumentException("Audition text is invalid");
        var future = execution.submit(() -> synthesizeAudio(voice, text, Math.min(properties.getMaxAudioBytes(), 1048576), beforeExternal));
        try { return future.get(properties.getOfficial().getQueueTimeout().plus(properties.getOfficial().getTimeout()).toMillis(), TimeUnit.MILLISECONDS); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    private byte[] synthesizeAudio(VoiceRuntimeBinding voice, String text, long maximumBytes, Runnable beforeExternal)
        throws InterruptedException, ExecutionException, TimeoutException
    {
        if (text == null || text.isBlank() || voice.providerVoiceRef() == null || voice.providerVoiceRef().isBlank())
            throw new IllegalArgumentException("Audition text is invalid");
        Duration timeout = properties.getOfficial().getTimeout();
        if (voice.executionDeadline() != null) timeout = timeout.compareTo(Duration.between(java.time.Instant.now(), voice.executionDeadline())) < 0
            ? timeout : Duration.between(java.time.Instant.now(), voice.executionDeadline());
        if (timeout.isNegative() || timeout.isZero()) throw new TimeoutException("Voice deadline elapsed");
        if (voice.executionDeadline() != null) timeout = timeout.compareTo(Duration.between(java.time.Instant.now(), voice.executionDeadline())) < 0
            ? timeout : Duration.between(java.time.Instant.now(), voice.executionDeadline());
        if (timeout.isNegative() || timeout.isZero()) throw new TimeoutException("Voice deadline elapsed");
        long deadline = System.nanoTime() + timeout.toNanos();
        OfficialServiceResolver.Resolved config = resolver.resolve(voice);
        AudioListener listener = new AudioListener(text, voice.providerVoiceRef(), maximumBytes);
        URI target = endpoint(config.endpoint(), config.model());
        remaining(deadline);
        remaining(deadline);
        beforeExternal.run();
        CompletableFuture<WebSocket> opening = null;
        try
        {
            opening = client.newWebSocketBuilder().connectTimeout(remaining(deadline))
                .header("Authorization", "Bearer " + config.credential()).header("User-Agent", "LN-Session/1")
                .buildAsync(target, listener);
            opening.get(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS);
            return pcmToWav(listener.await(remaining(deadline)), maximumBytes);
        }
        finally
        {
            listener.close();
            if (opening != null) opening.thenAccept(WebSocket::abort);
        }
    }

    private static Duration remaining(long deadline) throws TimeoutException
    {
        long nanos = deadline - System.nanoTime();
        if (nanos < 1000000) throw new TimeoutException("Voice deadline elapsed");
        return Duration.ofNanos(nanos);
    }

    private static String safeCause(Exception exception)
    {
        Throwable cause = exception instanceof ExecutionException && exception.getCause() != null ? exception.getCause() : exception;
        return cause.getClass().getSimpleName();
    }

    private static byte[] pcmToWav(byte[] pcm, long maximumBytes)
    {
        if (pcm.length == 0 || (pcm.length & 1) != 0 || pcm.length + 44L > maximumBytes)
            throw new IllegalArgumentException("Provider PCM length is invalid");
        ByteBuffer wav = ByteBuffer.allocate(pcm.length + 44).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length + 36);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return wav.array();
    }

    private URI endpoint(String configuredEndpoint, String model)
    {
        if (blank(configuredEndpoint) || blank(model))
        {
            throw new IllegalArgumentException("Official TTS endpoint is not configured");
        }
        URI base = URI.create(configuredEndpoint);
        if (!"wss".equalsIgnoreCase(base.getScheme()) || blank(base.getHost()))
        {
            throw new IllegalArgumentException("Official TTS endpoint must use wss");
        }
        String separator = base.getQuery() == null ? "?" : "&";
        return URI.create(base + separator + "model=" + URLEncoder.encode(model, StandardCharsets.UTF_8));
    }

    private static String configuredVoice(TtsSynthesisWork work)
    {
        String voice = work.voice().providerVoiceRef();
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
        private volatile String stage = "open";
        private boolean audioDone;
        private boolean responseDone;
        private volatile boolean closed;

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
            if (closed) { webSocket.abort(); return; }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            if (closed || completed.isDone()) return CompletableFuture.completedFuture(null);
            if ((long) frames.length() + data.length() > maxAudioBytes * 2 + 65536)
            {
                completed.completeExceptionally(new IllegalArgumentException("Provider frame exceeds limit"));
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
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
                stage = "session.created";
                socket.sendText(event("session.update", java.util.Map.of("session", java.util.Map.of("voice", voice, "mode", "commit",
                        "language_type", "Chinese", "response_format", "pcm", "sample_rate", 24000))), true);
            }
            else if ("session.updated".equals(type))
            {
                stage = "session.updated";
                socket.sendText(event("input_text_buffer.append", java.util.Map.of("text", text)), true);
                socket.sendText(event("input_text_buffer.commit"), true);
            }
            else if ("response.audio.delta".equals(type))
            {
                stage = "response.audio.delta";
                JsonNode delta = message.get("delta");
                if (delta == null || !delta.isTextual())
                {
                    throw new IllegalArgumentException("Provider audio delta is invalid");
                }
                byte[] bytes = Base64.getDecoder().decode(delta.asText());
                if (audio.size() + bytes.length + 44L > maxAudioBytes)
                {
                    throw new IllegalArgumentException("Provider audio exceeds the configured maximum");
                }
                audio.writeBytes(bytes);
            }
            else if ("response.audio.done".equals(type))
            {
                stage = "response.audio.done";
                audioDone = true;
            }
            else if ("response.done".equals(type))
            {
                if (!"completed".equals(message.path("response").path("status").asText()) || !audioDone)
                {
                    stage = "provider.error";
                    completed.completeExceptionally(new IllegalArgumentException("Provider did not finish TTS audio"));
                }
                else
                {
                    stage = "response.done";
                    responseDone = true;
                    socket.sendText(event("session.finish"), true);
                }
            }
            else if ("session.finished".equals(type))
            {
                stage = "session.finished";
                if (responseDone && audioDone) completed.complete(audio.toByteArray());
                else completed.completeExceptionally(new IllegalArgumentException("Provider finished without TTS audio"));
            }
            else if ("error".equals(type))
            {
                stage = "provider.error";
                completed.completeExceptionally(new IllegalArgumentException("Provider rejected the TTS request"));
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            stage = "socket.error";
            completed.completeExceptionally(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            stage = "socket.closed";
            completed.completeExceptionally(new IllegalStateException("Provider closed before audio completed"));
            return CompletableFuture.completedFuture(null);
        }

        private String stage()
        {
            return stage;
        }

        private void close()
        {
            closed = true;
            if (socket != null) socket.abort();
        }

        private byte[] await(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException
        {
            return completed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
