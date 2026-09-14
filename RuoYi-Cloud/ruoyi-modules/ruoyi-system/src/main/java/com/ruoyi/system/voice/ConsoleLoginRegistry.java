package com.ruoyi.system.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.system.api.model.LoginUser;

/** Process-local mapping intentionally makes old DEBUG grants fail closed after a system restart. */
@Component
public class ConsoleLoginRegistry
{
    private final ConcurrentHashMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private final TokenService tokenService;

    public ConsoleLoginRegistry(TokenService tokenService) { this.tokenService = tokenService; }

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
        LoginUser current = tokenService.getLoginUser(registration.consoleToken());
        return current != null && current.getUserid() != null && current.getUserid() == accountId
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
