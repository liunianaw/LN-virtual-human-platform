package com.ruoyi.session.runtime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Dedicated administrator context; no fabricated business Session or business billing. */
@Service
public class OfficialVoiceAuditionServiceImpl implements IOfficialVoiceAuditionService
{
    private final TtsRuntimeAdapterRegistry adapters;
    public OfficialVoiceAuditionServiceImpl(TtsRuntimeAdapterRegistry adapters) { this.adapters = adapters; }

    @Override public byte[] audition(Audition request)
    {
        if (request == null || request.voiceVersionId() <= 0 || request.serviceId() <= 0 || request.serviceRevision() <= 0
            || request.voiceAlias() == null || request.voiceAlias().isBlank() || request.voiceAlias().length() > 128
            || request.text() == null || request.text().isBlank() || request.text().codePointCount(0, request.text().length()) > 200)
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "AUDITION_INVALID", "Invalid audition");
        VoiceRuntimeBinding voice = new VoiceRuntimeBinding(request.voiceVersionId(), TtsProviderKind.OFFICIAL,
            request.voiceAlias(), request.serviceId(), request.serviceRevision());
        try { return adapters.requireAdapter(voice).audition(voice, request.text(), () -> { }); }
        catch (RuntimeProblem error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception error) { throw unavailable(); }
    }

    private static RuntimeProblem unavailable()
    { return new RuntimeProblem(HttpStatus.BAD_GATEWAY, "AUDITION_UNAVAILABLE", "Official audition unavailable"); }
}
