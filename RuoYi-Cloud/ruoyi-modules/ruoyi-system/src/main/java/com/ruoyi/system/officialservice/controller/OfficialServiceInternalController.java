package com.ruoyi.system.officialservice.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.system.officialservice.service.IOfficialServiceService;
import com.ruoyi.system.asset.service.MediaInternalTokenGuard;
import com.ruoyi.system.voice.InternalBearerGuard;

/** Service-to-service resolver. It intentionally has no gateway route. */
@RestController
@RequestMapping("/internal/v1/official-services")
public class OfficialServiceInternalController
{
    private final InternalBearerGuard guard; private final MediaInternalTokenGuard mediaGuard; private final IOfficialServiceService services;
    public OfficialServiceInternalController(InternalBearerGuard guard, MediaInternalTokenGuard mediaGuard, IOfficialServiceService services) { this.guard = guard; this.mediaGuard = mediaGuard; this.services = services; }
    @PostMapping("/{serviceId}/resolve") public IOfficialServiceService.ResolvedService resolve(@PathVariable long serviceId, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization, @RequestHeader(value = "X-LN-Internal-Token", required = false) String mediaToken, @RequestBody ResolveRequest input)
    { if (mediaToken != null) mediaGuard.require(mediaToken); else guard.requireSession(authorization); return services.resolve(serviceId, input.expectedServiceRevision(), input.purpose(), input.taskId(), input.voiceVersionId()); }
    public record ResolveRequest(long expectedServiceRevision, String purpose, Long taskId, Long voiceVersionId) { }
}
