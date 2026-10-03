package com.ruoyi.system.voice;

import com.ruoyi.common.voice.VoiceBinding;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/voice-bindings")
public class VoiceBindingInternalController
{
    private final InternalBearerGuard guard; private final VoiceBindingService bindings; private final VoiceExecutionMaterial material;
    public VoiceBindingInternalController(InternalBearerGuard guard,VoiceBindingService bindings,VoiceExecutionMaterial material)
    { this.guard=guard; this.bindings=bindings; this.material=material; }
    @GetMapping("/{id}") public VoiceBinding read(@PathVariable long id,@RequestHeader("Authorization") String bearer)
    { guard.requireSession(bearer); return bindings.available(id,false); }
    @GetMapping("/{id}/execution") public com.ruoyi.common.voice.VoiceProtocol.Execution execution(
        @PathVariable long id,@RequestHeader("Authorization") String bearer)
    { guard.requireSession(bearer); return material.resolve(id); }
}
