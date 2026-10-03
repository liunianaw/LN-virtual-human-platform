package com.ruoyi.session.runtime;

import java.time.Instant;

/** Safe event payload for a future WebSocket publisher. */
public record AudioSegmentEvent(String turnId, String segmentId, int ordinal, String mediaId, String mimeType,
        long durationMs, Instant expiresAt, boolean degraded, String actualVoiceDisplayName, String reasonCode)
{
    public AudioSegmentEvent(String turnId,String segmentId,int ordinal,String mediaId,String mimeType,long durationMs,Instant expiresAt)
    { this(turnId,segmentId,ordinal,mediaId,mimeType,durationMs,expiresAt,false,"",""); }
}
