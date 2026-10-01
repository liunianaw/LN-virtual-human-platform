package com.ruoyi.session.runtime;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Only the authenticated System service can request a bounded official Voice audition. */
@RestController
@RequestMapping("/internal/v1/official-voice-auditions")
public class InternalOfficialVoiceAuditionController
{
    private final InternalBearerGuard guard;
    private final OfficialDashScopeTtsRuntimeAdapter adapter;
    public InternalOfficialVoiceAuditionController(InternalBearerGuard guard, OfficialDashScopeTtsRuntimeAdapter adapter)
    { this.guard = guard; this.adapter = adapter; }
    @PostMapping
    public ResponseEntity<byte[]> audition(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestBody Audition request)
    {
        guard.requireSystem(authorization);
        if (request.voiceVersionId() <= 0 || request.serviceId() <= 0 || request.serviceRevision() <= 0
            || request.text() == null || request.text().isBlank() || request.text().length() > 200)
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "AUDITION_INVALID", "Invalid audition");
        try
        {
            byte[] audio = adapter.audition(new VoiceRuntimeBinding(request.voiceVersionId(), TtsProviderKind.OFFICIAL,
                request.voiceAlias(), request.serviceId(), request.serviceRevision()), request.text(), () -> { });
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(audio);
        }
        catch (InterruptedException error)
        { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception error) { throw unavailable(); }
    }
    private static RuntimeProblem unavailable()
    { return new RuntimeProblem(HttpStatus.BAD_GATEWAY, "AUDITION_UNAVAILABLE", "Official audition unavailable"); }
    public record Audition(long voiceVersionId, long serviceId, long serviceRevision, String voiceAlias, String text) { }
}
