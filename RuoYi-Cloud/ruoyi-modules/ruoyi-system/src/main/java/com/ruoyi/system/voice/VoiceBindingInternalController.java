package com.ruoyi.system.voice;

import com.ruoyi.common.voice.VoiceBinding;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/voice-bindings")
public class VoiceBindingInternalController
{
    private final InternalBearerGuard guard; private final VoiceBindingService bindings;
    public VoiceBindingInternalController(InternalBearerGuard guard,VoiceBindingService bindings) { this.guard=guard; this.bindings=bindings; }
    @GetMapping("/{id}") public VoiceBinding read(@PathVariable long id,@RequestHeader("Authorization") String bearer)
    { guard.requireSession(bearer); return bindings.available(id,false); }
}
