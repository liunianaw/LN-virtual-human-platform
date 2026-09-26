package com.ruoyi.session.runtime;

import java.util.Map;
import org.springframework.stereotype.Component;

/** The same server-side limits are returned by HTTP state and WSS ready. */
@Component
public class RuntimeLimits
{
    private final VoiceRuntimeProperties voice;
    public RuntimeLimits(VoiceRuntimeProperties voice) { this.voice = voice; }
    public Map<String, Object> current()
    {
        return Map.of("maxTextCodePoints", 8000, "maxCodePointsPerSegment", voice.getMaxCodePointsPerSegment(),
            "maxConcurrentSegments", 2, "maxBufferedSegments", voice.getMaxBufferedSegments(),
            "maxAudioBytes", voice.getMaxAudioBytes(), "ttsTimeoutSeconds", voice.getOfficial().getTimeout().toSeconds(),
            "turnTimeoutSeconds", 300, "playbackWaitSeconds", 60,
            "temporaryAudioTtlSeconds", voice.getTemporaryAudioTtl().toSeconds());
    }
}
