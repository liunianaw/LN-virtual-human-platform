package com.ruoyi.session.runtime;

import com.ruoyi.common.voice.VoiceProtocol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/voice-attempts")
public class VoiceAttemptController
{
    private final IVoiceOrchestrationService service;
    public VoiceAttemptController(IVoiceOrchestrationService service) { this.service=service; }
    @PostMapping("/{id}/authorize-dispatch") public VoiceProtocol.Authorized authorize(@PathVariable long id,
        @RequestHeader("Authorization") String bearer,@RequestBody VoiceProtocol.Permit request)
    { require(bearer); return service.authorize(id,request); }
    @PostMapping("/{id}/events") public java.util.Map<String,Boolean> event(@PathVariable long id,
        @RequestHeader("Authorization") String bearer,@RequestBody VoiceProtocol.Event request)
    { require(bearer); service.event(id,request); return java.util.Map.of("accepted",true); }
    @GetMapping public java.util.List<java.util.Map<String,Object>> diagnostics(@RequestHeader("Authorization") String bearer,
        @RequestParam(required=false) Long accountId)
    { new InternalBearerGuard().requireSystem(bearer); return service.diagnostics(accountId); }
    private void require(String bearer)
    {
        String secret=System.getenv("LN_VOICE_CALLBACK_BEARER");
        if(secret==null || secret.length()<32 || bearer==null || !MessageDigest.isEqual(("Bearer "+secret).getBytes(StandardCharsets.UTF_8),bearer.getBytes(StandardCharsets.UTF_8)))
            throw new RuntimeProblem(org.springframework.http.HttpStatus.UNAUTHORIZED,"INTERNAL_AUTH_REQUIRED","Voice executor identity required");
    }
}
