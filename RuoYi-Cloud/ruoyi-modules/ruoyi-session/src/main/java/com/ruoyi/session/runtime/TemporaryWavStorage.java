package com.ruoyi.session.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * A private, process-local hand-off location for M2 runtime audio. Files are
 * never exposed through a static resource mapping or a public URL.
 */
@Component
public class TemporaryWavStorage
{
    private final VoiceRuntimeProperties properties;

    public TemporaryWavStorage(VoiceRuntimeProperties properties)
    {
        this.properties = properties;
    }

    public StoredWav store(byte[] audio)
    {
        long durationMs = WavAudio.requireMonoPcm16Khz(audio, properties.getMaxAudioBytes());
        String mediaId = UUID.randomUUID().toString();
        String objectKey = mediaId + ".wav";
        try
        {
            Path directory = Path.of(properties.getTemporaryAudioDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Files.write(directory.resolve(objectKey), audio);
            TemporaryAudioReference reference = new TemporaryAudioReference(mediaId, "LOCAL_TEMP", "session-audio", objectKey,
                    Instant.now().plus(properties.getTemporaryAudioTtl()));
            return new StoredWav(reference, durationMs, audio.length);
        }
        catch (IOException | InvalidPathException exception)
        {
            throw new IllegalStateException("Temporary audio storage is unavailable", exception);
        }
    }

    public record StoredWav(TemporaryAudioReference reference, long durationMs, long bytes)
    {
    }
}
