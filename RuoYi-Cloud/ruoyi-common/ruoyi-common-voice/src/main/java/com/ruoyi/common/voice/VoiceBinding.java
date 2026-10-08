package com.ruoyi.common.voice;

import java.util.Map;

/** Immutable semantic configuration. Credentials and signed URLs never belong here. */
public record VoiceBinding(String voiceVersionId, String providerType, String serviceId, long serviceRevision,
    String modelId, String modelRevision, String capabilityVersion, String providerVoiceRef, String language,
    Map<String, Double> parameters, String referenceAssetId, String referenceText,
    VoiceBinding fallback, boolean allowVoiceChange, String policyVersion, String endpoint)
{
    public VoiceBinding primaryOnly()
    { return new VoiceBinding(voiceVersionId, providerType, serviceId, serviceRevision, modelId, modelRevision,
        capabilityVersion, providerVoiceRef, language, parameters, referenceAssetId, referenceText, null, false, policyVersion, endpoint); }
}
