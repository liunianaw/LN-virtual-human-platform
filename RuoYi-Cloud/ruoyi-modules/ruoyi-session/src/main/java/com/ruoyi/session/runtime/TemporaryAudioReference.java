package com.ruoyi.session.runtime;

import java.time.Instant;

/** Storage metadata is held internally and is never returned through the runtime API. */
public record TemporaryAudioReference(String mediaId, String storageProvider, String bucket, String objectKey,
        Instant expiresAt)
{
    public TemporaryAudioReference
    {
        if (mediaId == null || mediaId.isBlank() || storageProvider == null || storageProvider.isBlank()
                || bucket == null || bucket.isBlank() || objectKey == null || objectKey.isBlank() || expiresAt == null)
        {
            throw new IllegalArgumentException("Temporary audio must have an internal storage reference and expiry");
        }
    }
}
