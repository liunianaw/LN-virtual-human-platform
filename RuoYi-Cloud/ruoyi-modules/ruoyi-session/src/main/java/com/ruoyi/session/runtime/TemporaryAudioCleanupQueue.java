package com.ruoyi.session.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.stereotype.Component;

/** Queue consumed by the later storage-cleanup worker; it never contains credentials or signed URLs. */
@Component
public class TemporaryAudioCleanupQueue
{
    private final PersistentRuntimeStore store;
    private final ConcurrentLinkedQueue<TemporaryAudioReference> pending = new ConcurrentLinkedQueue<>();

    public TemporaryAudioCleanupQueue(PersistentRuntimeStore store)
    {
        this.store = store;
    }

    public void schedule(TemporaryAudioReference reference)
    {
        store.scheduleCleanup(reference);
        pending.offer(reference);
    }

    public List<TemporaryAudioReference> drain(int limit)
    {
        if (limit <= 0)
        {
            throw new IllegalArgumentException("Cleanup limit must be positive");
        }
        List<TemporaryAudioReference> references = new ArrayList<>(Math.min(limit, 64));
        for (int i = 0; i < limit; i++)
        {
            TemporaryAudioReference reference = pending.poll();
            if (reference == null)
            {
                break;
            }
            references.add(reference);
        }
        return List.copyOf(references);
    }
}
