package com.ruoyi.session.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal strict validator for the WAV contract accepted by the runtime. */
final class WavAudio
{
    private static final int WAV_HEADER_MIN_BYTES = 44;

    private WavAudio()
    {
    }

    static long requireMonoPcm16Khz(byte[] audio, long maximumBytes)
    {
        if (audio == null || audio.length < WAV_HEADER_MIN_BYTES || audio.length > maximumBytes
                || !matches(audio, 0, "RIFF") || !matches(audio, 8, "WAVE"))
        {
            throw new IllegalArgumentException("Audio is not a supported WAV payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
        if (Integer.toUnsignedLong(buffer.getInt(4)) != audio.length - 8L)
            throw new IllegalArgumentException("Audio WAV length is invalid");
        int offset = 12;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int dataBytes = -1;
        boolean pcm = false;
        boolean formatSeen = false;
        while (offset + 8 <= audio.length)
        {
            int chunkSize = buffer.getInt(offset + 4);
            if (chunkSize < 0 || offset + 8L + chunkSize > audio.length)
            {
                throw new IllegalArgumentException("Audio WAV chunk is malformed");
            }
            if (matches(audio, offset, "fmt "))
            {
                if (chunkSize < 16 || formatSeen)
                {
                    throw new IllegalArgumentException("Audio WAV format chunk is malformed");
                }
                pcm = buffer.getShort(offset + 8) == 1;
                channels = Short.toUnsignedInt(buffer.getShort(offset + 10));
                sampleRate = buffer.getInt(offset + 12);
                bitsPerSample = Short.toUnsignedInt(buffer.getShort(offset + 22));
                formatSeen = true;
                if (buffer.getInt(offset + 16) != 48000 || buffer.getShort(offset + 20) != 2)
                    throw new IllegalArgumentException("Audio WAV PCM alignment is invalid");
            }
            else if (matches(audio, offset, "data"))
            {
                if (dataBytes >= 0 || (chunkSize & 1) != 0) throw new IllegalArgumentException("Audio WAV data is invalid");
                dataBytes = chunkSize;
            }
            offset += 8 + chunkSize + (chunkSize & 1);
        }
        if (offset != audio.length || !pcm || channels != 1 || sampleRate != 24000 || bitsPerSample != 16 || dataBytes <= 0)
        {
            throw new IllegalArgumentException("Audio WAV must be mono PCM16 at 24kHz");
        }
        return Math.max(1L, dataBytes * 1000L / (sampleRate * channels * (bitsPerSample / 8)));
    }

    private static boolean matches(byte[] bytes, int offset, String value)
    {
        if (offset < 0 || offset + value.length() > bytes.length)
        {
            return false;
        }
        for (int index = 0; index < value.length(); index++)
        {
            if (bytes[offset + index] != (byte) value.charAt(index))
            {
                return false;
            }
        }
        return true;
    }
}
