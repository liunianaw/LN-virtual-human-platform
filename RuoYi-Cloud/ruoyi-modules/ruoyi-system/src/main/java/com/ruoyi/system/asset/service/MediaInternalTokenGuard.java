package com.ruoyi.system.asset.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.exception.ServiceException;

/** 仅为同进程环境注入的媒体 Worker 令牌提供内部接口门禁。 */
@Component
public class MediaInternalTokenGuard
{
    private static final String TOKEN_ENV = "RUOYI_MEDIA_INTERNAL_TOKEN";

    public void require(String presented)
    {
        String expected = System.getenv(TOKEN_ENV);
        if (expected == null || expected.isBlank())
            throw rejected();
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] presentedBytes = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        byte[] normalized = new byte[expectedBytes.length];
        System.arraycopy(presentedBytes, 0, normalized, 0, Math.min(presentedBytes.length, normalized.length));
        boolean matches = MessageDigest.isEqual(expectedBytes, normalized) & presentedBytes.length == expectedBytes.length;
        if (!matches)
            throw rejected();
    }

    private static ServiceException rejected()
    {
        return new ServiceException("内部 Worker 认证失败", HttpStatus.UNAUTHORIZED.value());
    }
}
