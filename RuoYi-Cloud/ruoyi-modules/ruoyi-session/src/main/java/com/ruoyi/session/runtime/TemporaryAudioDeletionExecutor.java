package com.ruoyi.session.runtime;

/** Storage-specific deletion is injected later; credentials remain inside that implementation. */
public interface TemporaryAudioDeletionExecutor
{
    void delete(TemporaryAudioReference reference);
}
