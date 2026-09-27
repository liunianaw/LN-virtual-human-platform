package com.ruoyi.system.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.constant.CacheConstants;
import com.ruoyi.common.redis.service.RedisService;
import com.ruoyi.system.api.model.LoginUser;

/** Process-local mapping intentionally makes old DEBUG grants fail closed after a system restart. */
@Component
public class ConsoleLoginRegistry
{
    private final ConcurrentHashMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private final RedisService redisService;

    public ConsoleLoginRegistry(RedisService redisService) { this.redisService = redisService; }

    public String register(LoginUser loginUser)
    {
        if (loginUser == null || loginUser.getToken() == null || loginUser.getToken().isBlank() || loginUser.getUserid() == null
                || loginUser.getExpireTime() == null) throw new IllegalStateException("Current console login is unavailable");
        String ref = hash(loginUser.getToken());
        registrations.put(ref, new Registration(loginUser.getUserid(), loginUser.getToken(), Instant.ofEpochMilli(loginUser.getExpireTime())));
        return ref;
    }

    public boolean isCurrent(String reference, long accountId)
    {
        Registration registration = registrations.get(reference);
        if (registration == null || registration.accountId() != accountId || !registration.expiresAt().isAfter(Instant.now())) return false;
        // LoginUser.token is the Redis session key, not the browser JWT.
        LoginUser current = redisService.getCacheObject(CacheConstants.LOGIN_TOKEN_KEY + registration.consoleToken());
        return current != null && current.getUserid() != null && current.getUserid() == accountId
                && registration.consoleToken().equals(current.getToken())
                && current.getExpireTime() != null && current.getExpireTime() >= System.currentTimeMillis();
    }

    public Instant expiry(String reference)
    {
        Registration registration = registrations.get(reference);
        if (registration == null || !registration.expiresAt().isAfter(Instant.now())) throw new IllegalStateException("Console login is no longer active");
        return registration.expiresAt();
    }

    private static String hash(String value)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private record Registration(long accountId, String consoleToken, Instant expiresAt) { }
}
