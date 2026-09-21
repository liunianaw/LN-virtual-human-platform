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

    public byte[] read(TemporaryAudioReference reference)
    {
        if (!"LOCAL_TEMP".equals(reference.storageProvider()) || !"session-audio".equals(reference.bucket())
            || !reference.expiresAt().isAfter(Instant.now())) throw new IllegalArgumentException("Temporary audio is unavailable");
        try
        {
            Path directory = Path.of(properties.getTemporaryAudioDirectory()).toAbsolutePath().normalize();
            Path file = directory.resolve(reference.objectKey()).normalize();
            if (!file.startsWith(directory) || !Files.isRegularFile(file)) throw new IllegalArgumentException("Temporary audio is unavailable");
            return Files.readAllBytes(file);
        }
        catch (IOException | InvalidPathException exception) { throw new IllegalArgumentException("Temporary audio is unavailable", exception); }
    }

    public void delete(TemporaryAudioReference reference)
    {
        if (!"LOCAL_TEMP".equals(reference.storageProvider()) || !"session-audio".equals(reference.bucket())) return;
        try
        {
            Path directory = Path.of(properties.getTemporaryAudioDirectory()).toAbsolutePath().normalize();
            Path file = directory.resolve(reference.objectKey()).normalize();
            if (!file.startsWith(directory)) throw new IllegalArgumentException("Invalid temporary audio path");
            Files.deleteIfExists(file);
        }
        catch (IOException | InvalidPathException exception) { throw new IllegalStateException("Temporary audio deletion failed", exception); }
    }

    public record StoredWav(TemporaryAudioReference reference, long durationMs, long bytes)
    {
    }
}
