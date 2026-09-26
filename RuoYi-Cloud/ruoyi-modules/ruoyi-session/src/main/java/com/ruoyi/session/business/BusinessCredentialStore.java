package com.ruoyi.session.business;

import com.ruoyi.session.runtime.RuntimeProblem;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Optional short-lived Tool query credential. Only encrypted bytes enter Redis; no HTTP read endpoint exists. */
@Component
public class BusinessCredentialStore
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    public BusinessCredentialStore(StringRedisTemplate redis) { this.redis = redis; }

    public void put(long sessionId, String credential, Instant sessionExpiry)
    {
        validate(credential);
        Duration ttl = Duration.between(Instant.now(), sessionExpiry);
        if (ttl.isNegative() || ttl.isZero()) throw new RuntimeProblem(HttpStatus.GONE, "SESSION_ENDED", "Session has ended.");
        if (ttl.compareTo(Duration.ofMinutes(15)) > 0) ttl = Duration.ofMinutes(15);
        try
        {
            byte[] key = key();
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(Long.toString(sessionId).getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(credential.getBytes(StandardCharsets.UTF_8));
            redis.opsForValue().set(name(sessionId), Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array()), ttl);
        }
        catch (RuntimeProblem error) { throw error; }
        catch (Exception error) { throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "QUERY_CREDENTIAL_UNAVAILABLE", "Query credential storage is unavailable."); }
    }

    public void preflight(String credential)
    {
        validate(credential);
        key();
    }

    public byte[] fingerprint(String credential)
    {
        if (credential == null) return new byte[32];
        preflight(credential);
        try
        {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return mac.doFinal(credential.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception error) { throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE,
            "QUERY_CREDENTIAL_UNAVAILABLE", "Query credential is unavailable."); }
    }

    private static void validate(String credential)
    {
        if (credential == null || credential.isBlank() || credential.length() > 4096
            || credential.codePoints().anyMatch(cp -> Character.isISOControl(cp)))
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "QUERY_CREDENTIAL_INVALID", "Query credential is invalid.");
    }

    /** Restricted to trusted Tool execution; never put the result in a Prompt or browser response. */
    public String readForTool(long sessionId)
    {
        try
        {
            String value = redis.opsForValue().get(name(sessionId));
            if (value == null) return null;
            byte[] data = Base64.getDecoder().decode(value);
            if (data.length <= 28) throw new IllegalArgumentException("Invalid encrypted credential");
            byte[] nonce = java.util.Arrays.copyOfRange(data, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(data, 12, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(Long.toString(sessionId).getBytes(StandardCharsets.US_ASCII));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (Exception error) { throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "QUERY_CREDENTIAL_UNAVAILABLE", "Query credential is unavailable."); }
    }

    public void remove(long sessionId) { redis.delete(name(sessionId)); }

    private static byte[] key()
    {
        String encoded = System.getenv("LN_BUSINESS_CREDENTIAL_ENCRYPTION_KEY");
        if (encoded == null || encoded.isBlank()) throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE,
            "QUERY_CREDENTIAL_NOT_CONFIGURED", "Query credential encryption is unavailable.");
        try
        {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) throw new IllegalArgumentException("Expected 32-byte key");
            return decoded;
        }
        catch (IllegalArgumentException error) { throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE,
            "QUERY_CREDENTIAL_NOT_CONFIGURED", "Query credential encryption is unavailable."); }
    }

    private static String name(long sessionId) { return "ln:business:credential:" + sessionId; }
}
