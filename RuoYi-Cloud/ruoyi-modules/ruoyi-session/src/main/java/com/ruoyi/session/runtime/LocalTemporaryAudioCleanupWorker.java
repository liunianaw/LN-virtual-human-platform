package com.ruoyi.session.runtime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Replays persisted cleanup candidates after restart; physical deletion is restricted to the configured local temp root. */
@Component
public class LocalTemporaryAudioCleanupWorker
{
    private final PersistentRuntimeStore store;
    private final TemporaryWavStorage storage;

    public LocalTemporaryAudioCleanupWorker(PersistentRuntimeStore store, TemporaryWavStorage storage)
    { this.store = store; this.storage = storage; }

    @Scheduled(fixedDelayString = "${LN_SESSION_RUNTIME_CLEANUP_SWEEP_MS:30000}")
    public void cleanup()
    {
        for (TemporaryAudioReference audio : store.cleanupCandidates(64))
        {
            try { storage.delete(audio); store.markDeleted(audio); }
            catch (RuntimeException ignored) { store.markDeleteFailed(audio, "TEMPORARY_AUDIO_DELETE_FAILED"); }
        }
    }
}
