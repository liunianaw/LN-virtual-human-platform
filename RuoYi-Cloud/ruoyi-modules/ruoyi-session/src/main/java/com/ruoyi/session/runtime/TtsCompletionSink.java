package com.ruoyi.session.runtime;

/** Callback used by a protected TTS adapter after it stores a temporary audio object. */
public interface TtsCompletionSink
{
    default void beforeExternal(TtsSynthesisWork work) { }
    AudioReadyResult onAudioReady(RuntimePrincipal principal, AudioReadyInput input);

    /** A provider failure must be persisted as a failure, never left looking like an audio success. */
    void onAudioFailed(RuntimePrincipal principal, TtsSynthesisWork work, String failureCode);
}
