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

    public synchronized StoredWav store(byte[] audio)
    {
        long durationMs = WavAudio.requireMonoPcm16Khz(audio, properties.getMaxAudioBytes());
        String mediaId = UUID.randomUUID().toString();
        String objectKey = mediaId + ".wav";
        try
        {
            Path directory = Path.of(properties.getTemporaryAudioDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("Invalid temporary audio directory");
            long total = audio.length;
            int entries = 0;
            try (var files = Files.newDirectoryStream(directory, "*.wav"))
            {
                for (Path file : files)
                {
                    if (++entries >= 4096) throw new IOException("Temporary audio file capacity reached");
                    if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) total += Files.size(file);
                    if (total > properties.getMaxTemporaryAudioBytes()) throw new IOException("Temporary audio byte capacity reached");
                }
            }
            if (total > properties.getMaxTemporaryAudioBytes()) throw new IOException("Temporary audio byte capacity reached");
            Path file = directory.resolve(objectKey);
            try { Files.write(file, audio, java.nio.file.StandardOpenOption.CREATE_NEW); }
            catch (IOException error)
            {
                try { Files.deleteIfExists(file); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
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
            if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Temporary audio is unavailable");
            try (var input = Files.newInputStream(file))
            {
                byte[] bytes = input.readNBytes(Math.toIntExact(properties.getMaxAudioBytes() + 1));
                WavAudio.requireMonoPcm16Khz(bytes, properties.getMaxAudioBytes());
                return bytes;
            }
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

    /** Only expired UUID WAVs in this private directory; never recurse or follow links. */
    public void cleanupExpiredFiles()
    {
        Path directory = Path.of(properties.getTemporaryAudioDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) return;
        Instant cutoff = Instant.now().minus(properties.getTemporaryAudioTtl()).minusSeconds(60);
        try (var files = Files.newDirectoryStream(directory, "*.wav"))
        {
            int scanned = 0;
            for (Path file : files)
            {
                if (++scanned > 4096) break;
                if (!file.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.wav")
                    || Files.isSymbolicLink(file) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                try
                {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
                }
                catch (IOException error) { /* The next bounded sweep retries this expired file. */ }
            }
        }
        catch (IOException error) { throw new IllegalStateException("Temporary audio reconciliation failed", error); }
    }
}
