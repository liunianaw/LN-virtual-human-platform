package com.ruoyi.session.runtime;

public interface IOfficialVoiceAuditionService
{
    byte[] audition(Audition request);
    record Audition(long voiceVersionId, long serviceId, long serviceRevision, String voiceAlias, String text) { }
}
