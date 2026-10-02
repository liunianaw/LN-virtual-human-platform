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
    private final IOfficialVoiceAuditionService service;
    public InternalOfficialVoiceAuditionController(InternalBearerGuard guard, IOfficialVoiceAuditionService service)
    { this.guard = guard; this.service = service; }
    @PostMapping
    public ResponseEntity<byte[]> audition(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestBody IOfficialVoiceAuditionService.Audition request)
    {
        guard.requireSystem(authorization);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.audition(request));
    }
}
