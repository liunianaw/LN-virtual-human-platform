package com.ruoyi.session.runtime;

public interface IOfficialVoiceAuditionService
{
    byte[] audition(Audition request);
    record Audition(long voiceVersionId, long administratorId, String auditionKey, String text) { }
}
