package com.ruoyi.session.runtime;

import java.time.Instant;

/** Safe event payload for a future WebSocket publisher. */
public record AudioSegmentEvent(String turnId, String segmentId, int ordinal, String mediaId, String mimeType,
        long durationMs, Instant expiresAt)
{
}
