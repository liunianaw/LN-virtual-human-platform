package com.ruoyi.session.runtime;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Selects only the provider fixed by the trusted Voice binding. */
@Component
public class TtsRuntimeAdapterRegistry
{
    private final Map<TtsProviderKind, TtsRuntimeAdapter> adapters = new EnumMap<>(TtsProviderKind.class);

    public TtsRuntimeAdapterRegistry(List<TtsRuntimeAdapter> adapters)
    {
        for (TtsRuntimeAdapter adapter : adapters)
        {
            if (this.adapters.put(adapter.providerKind(), adapter) != null)
            {
                throw new IllegalStateException("Only one TTS adapter may serve each provider kind");
            }
        }
    }

    public TtsRuntimeAdapter requireAdapter(VoiceRuntimeBinding voice)
    {
        TtsRuntimeAdapter adapter = adapters.get(voice.providerKind());
        if (adapter == null)
        {
            throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "TTS_PROVIDER_NOT_CONFIGURED",
                    "No protected adapter is installed for this Voice provider.");
        }
        return adapter;
    }
    /** Temporary migration route; real provider adapters move to the executor in stage three. */
    public boolean usesLegacyBridge(String providerType)
    { return "DASHSCOPE_QWEN_TTS".equals(providerType); }
}
