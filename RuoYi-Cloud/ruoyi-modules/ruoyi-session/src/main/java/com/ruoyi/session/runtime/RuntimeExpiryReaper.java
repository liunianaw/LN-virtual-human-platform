package com.ruoyi.session.runtime;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Stops only active DEBUG runtime turns once no valid Console DEBUG grant remains. */
@Component
@EnableScheduling
public class RuntimeExpiryReaper
{
    private final PersistentRuntimeStore store;
    private final SpeakOnlyRuntimeService runtime;
    private final ChatTurnStore chatStore;
    private final IChatRuntimeService chat;

    public RuntimeExpiryReaper(PersistentRuntimeStore store, SpeakOnlyRuntimeService runtime,
        ChatTurnStore chatStore, IChatRuntimeService chat)
    {
        this.store = store;
        this.runtime = runtime;
        this.chatStore = chatStore;
        this.chat = chat;
    }

    @Scheduled(fixedDelayString = "${LN_SESSION_RUNTIME_REVOKE_SWEEP_MS:30000}")
    public void revokeExpiredDebugRuntimeTurns()
    {
        for (Long sessionId : store.expiredDebugRuntimeSessionIds())
        {
            try { runtime.revokeSession(sessionId); }
            finally
            {
                try { chatStore.expireDebug(sessionId); }
                finally { chat.cancelPrior(sessionId, Long.MAX_VALUE); }
            }
        }
    }
}
