package com.ruoyi.session.runtime;

import java.time.Instant;

/** Storage metadata is held internally and is never returned through the runtime API. */
public record TemporaryAudioReference(String mediaId, String deletionReference, Instant expiresAt)
{
    public TemporaryAudioReference
    {
        if (mediaId == null || mediaId.isBlank() || deletionReference == null || deletionReference.isBlank()
                || expiresAt == null)
        {
            throw new IllegalArgumentException("Temporary audio must have an internal storage reference and expiry");
        }
    }
}
