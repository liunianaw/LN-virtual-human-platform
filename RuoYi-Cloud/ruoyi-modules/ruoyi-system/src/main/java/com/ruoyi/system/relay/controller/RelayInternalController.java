package com.ruoyi.system.relay.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.system.relay.service.IRelayService;
import com.ruoyi.system.voice.InternalBearerGuard;

/** Only the session service can request a current Relay token for a confirmed Session binding. */
@RestController
@RequestMapping("/internal/v1/relay-services")
public class RelayInternalController
{
    private final InternalBearerGuard guard;
    private final IRelayService relays;
    public RelayInternalController(InternalBearerGuard guard, IRelayService relays)
    { this.guard = guard; this.relays = relays; }

    @PostMapping("/resolve")
    public IRelayService.ResolvedRelay resolve(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestBody IRelayService.RuntimeBinding binding)
    { guard.requireSession(authorization); return relays.resolve(binding); }
}
