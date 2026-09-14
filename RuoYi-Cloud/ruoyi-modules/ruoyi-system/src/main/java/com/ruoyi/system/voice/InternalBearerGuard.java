package com.ruoyi.system.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.exception.ServiceException;

/** Fixed service-to-service identity for session verification; never a browser header. */
@Component
public class InternalBearerGuard
{
    public void requireSession(String authorization)
    {
        String expected = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        if (expected == null || expected.isBlank() || authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new ServiceException("内部服务身份无效", HttpStatus.UNAUTHORIZED.value());
    }
}
