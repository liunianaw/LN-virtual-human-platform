package com.ruoyi.common.voice;

import java.util.List;
import java.util.Map;

/** The same descriptor drives management validation and execution admission. */
public record VoiceCapability(int schemaVersion, String providerType, String adapterVersion,
    String capabilityVersion, String modelId, String modelRevision, List<String> languages,
    List<Voice> voices, int maxInputChars, Map<String, Range> parameters, boolean referenceVoice,
    boolean audioStreaming, String cancelMode, String queryMode, boolean fallbackTarget, Integer referenceMaxDurationMs)
{
    public record Voice(String id, String displayName, List<String> languages) { }
    public record Range(double minimum, double maximum, double defaultValue) { }

    public void validate(String alias, String language, Map<String, ?> values, boolean reference)
    {
        if (schemaVersion != 1 || maxInputChars < 1 || !languages.contains(language)
            || reference && !referenceVoice || !reference && voices.stream().noneMatch(v -> v.id().equals(alias) && v.languages().contains(language)))
            throw new IllegalArgumentException("VOICE_CAPABILITY_UNSUPPORTED");
        validateParameters(values);
    }

    public void validateParameters(Map<String, ?> values)
    {
        if (values == null) throw new IllegalArgumentException("VOICE_PARAMETER_INVALID");
        values.forEach((key, value) -> {
            Range range = parameters.get(key);
            if (range == null || !(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < range.minimum || n.doubleValue() > range.maximum)
                throw new IllegalArgumentException("VOICE_PARAMETER_INVALID");
        });
    }
}
