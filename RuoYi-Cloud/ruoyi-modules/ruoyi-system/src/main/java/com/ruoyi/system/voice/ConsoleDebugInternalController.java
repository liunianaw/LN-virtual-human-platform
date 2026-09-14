package com.ruoyi.system.voice;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session's only callback: confirm a transient console-login reference is still backed by the login store. */
@RestController
@RequestMapping("/internal/v1/console-debug-grants")
public class ConsoleDebugInternalController
{
    private final InternalBearerGuard guard;
    private final ConsoleLoginRegistry logins;

    public ConsoleDebugInternalController(InternalBearerGuard guard, ConsoleLoginRegistry logins)
    {
        this.guard = guard;
        this.logins = logins;
    }

    @PostMapping("/verify")
    public ResponseEntity<Void> verify(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @RequestBody VerifyBody body)
    {
        guard.requireSession(authorization);
        return logins.isCurrent(body.issuerConsoleRef(), body.accountId()) ? ResponseEntity.ok().build() : ResponseEntity.status(401).build();
    }

    public record VerifyBody(String issuerConsoleRef, long accountId) { }
}
