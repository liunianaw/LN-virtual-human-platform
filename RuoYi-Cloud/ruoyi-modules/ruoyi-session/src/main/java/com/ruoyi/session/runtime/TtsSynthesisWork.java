package com.ruoyi.session.runtime;

/** In-memory work passed to a selected adapter; never expose this record to browser JSON or logs. */
public record TtsSynthesisWork(String turnId, long generation, String segmentId, int ordinal, String text,
        RuntimePrincipal principal)
{
    public VoiceRuntimeBinding voice()
    {
        return principal.voice();
    }
}
