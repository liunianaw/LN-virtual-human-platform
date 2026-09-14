package com.ruoyi.session.runtime;

import org.springframework.stereotype.Component;

/** Common completion handling so no provider can claim success before WAV validation and local storage succeed. */
@Component
public class TtsAdapterSupport
{
    private final TemporaryWavStorage temporaryWavStorage;

    public TtsAdapterSupport(TemporaryWavStorage temporaryWavStorage)
    {
        this.temporaryWavStorage = temporaryWavStorage;
    }

    public void complete(TtsSynthesisWork work, TtsCompletionSink completionSink, byte[] audio)
    {
        TemporaryWavStorage.StoredWav stored = temporaryWavStorage.store(audio);
        completionSink.onAudioReady(work.principal(), new AudioReadyInput(work.turnId(), work.generation(), work.segmentId(),
                work.ordinal(), "audio/wav", stored.durationMs(), stored.bytes(), stored.reference()));
    }

    public void fail(TtsSynthesisWork work, TtsCompletionSink completionSink, String code)
    {
        completionSink.onAudioFailed(work.principal(), work, code);
    }
}
