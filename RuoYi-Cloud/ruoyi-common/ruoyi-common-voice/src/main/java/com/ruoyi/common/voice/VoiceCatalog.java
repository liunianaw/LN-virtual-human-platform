package com.ruoyi.common.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trusted deployment manifest, loaded once; never accepts browser-supplied adapter code. */
public final class VoiceCatalog
{
    private final Map<String, VoiceCapability> entries = new LinkedHashMap<>();
    public VoiceCatalog()
    {
        try
        {
            ObjectMapper json = new ObjectMapper();
            try (var stream = VoiceCatalog.class.getResourceAsStream("/voice/capabilities.json"))
            {
                for (var value : json.readValue(stream, VoiceCapability[].class))
                    if (!value.providerType().startsWith("TEST_") || "true".equals(System.getenv("LN_VOICE_ENABLE_FAKE")))
                        entries.put(value.providerType(), value);
            }
            String manifest = System.getenv("LN_VOICE_CAPABILITY_MANIFEST");
            if (manifest != null && !manifest.isBlank())
            {
                if (Files.size(Path.of(manifest)) > 262144) throw new IllegalArgumentException("Voice manifest too large");
                for (var value : json.readValue(Files.readString(Path.of(manifest)), VoiceCapability[].class))
                {
                    if (value.schemaVersion() != 1 || entries.putIfAbsent(value.providerType(), value) != null)
                        throw new IllegalArgumentException("Duplicate or unsupported Voice capability");
                }
            }
        }
        catch (Exception error) { throw new IllegalStateException("Installed Voice capabilities are invalid", error); }
    }
    public static String canonical(String provider)
    { return "DASHSCOPE_BEIJING".equals(provider) ? "DASHSCOPE_QWEN_TTS" : provider; }
    public VoiceCapability require(String provider, String model)
    {
        VoiceCapability value = entries.get(canonical(provider));
        if (value == null || !value.modelId().equals(model)) throw new IllegalArgumentException("VOICE_CAPABILITY_UNSUPPORTED");
        return value;
    }
    public List<VoiceCapability> list() { return List.copyOf(entries.values()); }
    public static void validateEndpoint(String endpoint, String provider)
    {
        var uri = java.net.URI.create(endpoint);
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getHost() == null)
            throw new IllegalArgumentException("VOICE_ENDPOINT_INVALID");
        if ("DASHSCOPE_QWEN_TTS".equals(canonical(provider)))
        {
            if (!"wss".equals(uri.getScheme()) || !"dashscope.aliyuncs.com".equals(uri.getHost()) || uri.getPort() > 0 || !"/api-ws/v1/realtime".equals(uri.getPath()))
                throw new IllegalArgumentException("VOICE_ENDPOINT_INVALID");
        }
        else if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
            || !Arrays.asList(System.getenv().getOrDefault("LN_VOICE_ALLOWED_ENDPOINTS", "").split(",")).contains(endpoint))
            throw new IllegalArgumentException("VOICE_ENDPOINT_NOT_ALLOWLISTED");
    }
}
