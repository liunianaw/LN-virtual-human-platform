package com.ruoyi.session.runtime;
import org.springframework.stereotype.Service;
@Service
public class OfficialVoiceAuditionServiceImpl implements IOfficialVoiceAuditionService
{
    private final IVoiceOrchestrationService voices;
    public OfficialVoiceAuditionServiceImpl(IVoiceOrchestrationService voices) { this.voices=voices; }
    @Override public byte[] audition(Audition request)
    { if(request==null || request.administratorId()<=0 || request.voiceVersionId()<=0) throw VoiceAttemptStore.problem("AUDITION_INVALID");
      return voices.audition(request.administratorId(),request.voiceVersionId(),request.auditionKey(),request.text()); }
}
