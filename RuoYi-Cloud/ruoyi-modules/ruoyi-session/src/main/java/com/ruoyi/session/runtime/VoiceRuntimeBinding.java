package com.ruoyi.session.runtime;

/** Stable Voice references returned by trusted platform configuration, never browser input. */
public record VoiceRuntimeBinding(long voiceVersionId, TtsProviderKind providerKind, String providerVoiceRef,
        String relayVersionRef)
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
    }
}
