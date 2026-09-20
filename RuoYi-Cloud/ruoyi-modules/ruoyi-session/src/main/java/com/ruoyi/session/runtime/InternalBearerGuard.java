package com.ruoyi.session.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Guards the system-to-session boundary; browser credentials are never accepted here. */
@Component
public class InternalBearerGuard
{
    public void requireSystem(String authorization)
    {
        String expected = System.getenv("LN_SYSTEM_TO_SESSION_INTERNAL_BEARER");
        if (expected == null || expected.isBlank() || authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
        {
            throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "INTERNAL_AUTH_REQUIRED", "Trusted system identity is required.");
        }
    }
}
