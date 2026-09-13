package com.ruoyi.session.runtime;

import java.util.List;

/** A stale completion is ignored and its temporary object has already been queued for deletion. */
public record AudioReadyResult(boolean accepted, List<AudioSegmentEvent> events, List<TtsSynthesisWork> nextWork)
{
    static AudioReadyResult ignored()
    {
        return new AudioReadyResult(false, List.of(), List.of());
    }
}
