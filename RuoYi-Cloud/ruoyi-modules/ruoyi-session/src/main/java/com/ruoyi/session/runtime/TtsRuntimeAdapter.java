package com.ruoyi.session.runtime;

/**
 * Provider-specific code is supplied later as an application bean.  An adapter
 * receives a fixed Voice reference and text in memory only; it must obtain any
 * credential through its protected runtime source and must never log it.
 */
public interface TtsRuntimeAdapter
{
    TtsProviderKind providerKind();

    void submit(TtsSynthesisWork work, TtsCompletionSink completionSink);

    default byte[] audition(VoiceRuntimeBinding voice, String text, Runnable beforeExternal)
        throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException
    { throw new UnsupportedOperationException("Audition is not supported by this adapter"); }
}
