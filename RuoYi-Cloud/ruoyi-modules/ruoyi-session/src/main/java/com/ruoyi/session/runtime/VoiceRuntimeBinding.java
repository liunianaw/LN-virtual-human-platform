package com.ruoyi.session.runtime;

/** Stable Voice references returned by trusted platform configuration, never browser input. */
public record VoiceRuntimeBinding(long voiceVersionId, TtsProviderKind providerKind, String providerVoiceRef,
        Long officialServiceId, Long officialServiceRevision, com.ruoyi.common.voice.VoiceBinding execution, java.time.Instant executionDeadline)
{
    public VoiceRuntimeBinding(long version,TtsProviderKind kind,String voice,Long service,Long revision)
    { this(version,kind,voice,service,revision,null,null); }
    public VoiceRuntimeBinding(long version,TtsProviderKind kind,String voice,Long service,Long revision,com.ruoyi.common.voice.VoiceBinding execution)
    { this(version,kind,voice,service,revision,execution,null); }
    public VoiceRuntimeBinding withExecution(com.ruoyi.common.voice.VoiceBinding value)
    { return new VoiceRuntimeBinding(Long.parseLong(value.voiceVersionId()),providerKind,value.providerVoiceRef(),Long.parseLong(value.serviceId()),value.serviceRevision(),value,executionDeadline); }
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
