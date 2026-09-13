package com.ruoyi.session.runtime;

/** Callback used by a protected TTS adapter after it stores a temporary audio object. */
public interface TtsCompletionSink
{
    AudioReadyResult onAudioReady(RuntimePrincipal principal, AudioReadyInput input);
}
