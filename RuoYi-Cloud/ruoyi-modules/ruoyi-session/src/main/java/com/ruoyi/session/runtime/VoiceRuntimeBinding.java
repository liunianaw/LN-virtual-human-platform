package com.ruoyi.session.runtime;

/** Stable Voice references returned by trusted platform configuration, never browser input. */
public record VoiceRuntimeBinding(long voiceVersionId, TtsProviderKind providerKind, String providerVoiceRef,
        String relayVersionRef, Long officialServiceId, Long officialServiceRevision)
{
    public VoiceRuntimeBinding
    {
        if (voiceVersionId <= 0 || providerKind == null || providerVoiceRef == null || providerVoiceRef.isBlank())
        {
            throw new IllegalArgumentException("A fixed Voice version and provider voice reference are required");
        }
        if (providerKind == TtsProviderKind.RELAY && (relayVersionRef == null || relayVersionRef.isBlank()))
        {
            throw new IllegalArgumentException("Relay Voice requires a fixed Relay version reference");
        }
        if (providerKind == TtsProviderKind.OFFICIAL && relayVersionRef != null)
        {
            throw new IllegalArgumentException("Official Voice must not carry a Relay reference");
        }
        if (providerKind == TtsProviderKind.OFFICIAL && (officialServiceId == null || officialServiceId <= 0
                || officialServiceRevision == null || officialServiceRevision <= 0))
        {
            throw new IllegalArgumentException("Official Voice requires a fixed active service revision");
        }
        if (providerKind == TtsProviderKind.RELAY && (officialServiceId != null || officialServiceRevision != null))
        {
            throw new IllegalArgumentException("Relay Voice must not carry an official service reference");
        }
    }
}
