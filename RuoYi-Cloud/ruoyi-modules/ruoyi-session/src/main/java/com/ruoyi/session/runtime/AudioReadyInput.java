package com.ruoyi.session.runtime;

/** Internal completion from a selected adapter after audio has been written to temporary storage. */
public record AudioReadyInput(String turnId, long generation, String segmentId, int ordinal, String mimeType,
        long durationMs, long bytes, TemporaryAudioReference temporaryAudio, boolean degraded, String actualVoiceDisplayName, String reasonCode)
{
    public AudioReadyInput(String turnId,long generation,String segmentId,int ordinal,String mimeType,long durationMs,long bytes,TemporaryAudioReference temporaryAudio)
    { this(turnId,generation,segmentId,ordinal,mimeType,durationMs,bytes,temporaryAudio,false,"",""); }
}
