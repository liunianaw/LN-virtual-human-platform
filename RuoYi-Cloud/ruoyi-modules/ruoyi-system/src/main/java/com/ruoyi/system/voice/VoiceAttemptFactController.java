package com.ruoyi.system.voice;
import com.ruoyi.common.voice.VoiceAttemptFact;
import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;

@RestController
public class VoiceAttemptFactController {
    private final InternalBearerGuard guard;private final IVoiceAttemptFactService service;
    public VoiceAttemptFactController(InternalBearerGuard guard,IVoiceAttemptFactService service) { this.guard=guard;this.service=service; }
    @PostMapping("/internal/v1/voice-attempt-facts")
    public AjaxResult accept(@RequestHeader("Authorization") String bearer,@RequestBody VoiceAttemptFact fact) {
        guard.requireSession(bearer);service.accept(fact);return AjaxResult.success();
    }
}
