package com.ruoyi.session.runtime;

/** Stable Voice references returned by trusted platform configuration, never browser input. */
public record VoiceRuntimeBinding(long voiceVersionId, TtsProviderKind providerKind, String providerVoiceRef,
        Long officialServiceId, Long officialServiceRevision)
{
    public VoiceRuntimeBinding
    {
        if (voiceVersionId <= 0 || providerKind == null || providerVoiceRef == null || providerVoiceRef.isBlank())
        {
            throw new IllegalArgumentException("A fixed Voice version and provider voice reference are required");
        }
        if (officialServiceId == null || officialServiceId <= 0
                || officialServiceRevision == null || officialServiceRevision <= 0)
        {
            throw new IllegalArgumentException("Official Voice requires a fixed active service revision");
        }
    }
}
