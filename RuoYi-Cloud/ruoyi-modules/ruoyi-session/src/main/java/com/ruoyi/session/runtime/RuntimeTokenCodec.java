package com.ruoyi.session.runtime;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Canonical HMAC runtime tokens for browser grants. */
@Component
public class RuntimeTokenCodec
{
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Pattern KEY_VERSION = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private final ObjectMapper json;
    private final java.util.function.Function<String, String> environment;

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeTokenCodec(ObjectMapper json) { this(json, System::getenv); }
    RuntimeTokenCodec(ObjectMapper json, java.util.function.Function<String, String> environment)
    { this.json = json; this.environment = environment; }

    public String currentKeyVersion()
    {
        String value = environment.apply("LN_SESSION_RUNTIME_TOKEN_KEY_VERSION");
        return value == null || value.isBlank() ? "1" : value;
    }

    public String encodeV2(V2Claims claims)
    {
        if (claims == null || !KEY_VERSION.matcher(claims.keyVersion()).matches()) throw rejected();
        String signed = "ln2." + claims.keyVersion() + "." + ENCODER.encodeToString(canonical(claims).getBytes(StandardCharsets.UTF_8));
        return signed + "." + ENCODER.encodeToString(signWithKey(signed, claims.keyVersion()));
    }

    public V2Claims decodeV2(String authorization)
    {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 4096) throw rejected();
        String[] parts = authorization.substring(7).split("\\.", -1);
        if (parts.length != 4 || !"ln2".equals(parts[0]) || !KEY_VERSION.matcher(parts[1]).matches()
            || parts[2].length() > 2048 || parts[3].length() > 128) throw rejected();
        String signed = parts[0] + "." + parts[1] + "." + parts[2];
        if (!MessageDigest.isEqual(signWithKey(signed, parts[1]), decodeSignature(parts[3]))) throw rejected();
        try
        {
            String payload = new String(DECODER.decode(parts[2]), StandardCharsets.UTF_8);
            JsonNode node = json.readTree(payload);
            V2Claims claims = new V2Claims(parts[1], node.get("jti").asText(), Long.parseLong(node.get("accountId").asText()),
                Long.parseLong(node.get("applicationId").asText()), Long.parseLong(node.get("sessionId").asText()),
                node.get("source").asText(),
                Instant.ofEpochMilli(node.get("iat").asLong()), Instant.ofEpochMilli(node.get("exp").asLong()));
            if (!ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)).equals(parts[2])
                || !payload.equals(canonical(claims)) || !claims.expiresAt().isAfter(Instant.now())
                || claims.issuedAt().isAfter(Instant.now().plusSeconds(30))) throw rejected();
            return claims;
        }
        catch (RuntimeProblem error) { throw error; }
        catch (Exception error) { throw rejected(); }
    }

    private static String canonical(V2Claims value)
    {
        if (!value.tokenId().matches("[0-9a-f]{32}") || value.accountId() <= 0 || value.applicationId() <= 0
            || value.sessionId() <= 0 || !"BUSINESS_KEY".equals(value.source())
            || !value.expiresAt().isAfter(value.issuedAt())) throw rejected();
        return "{\"jti\":\"" + value.tokenId() + "\",\"accountId\":\"" + value.accountId()
            + "\",\"applicationId\":\"" + value.applicationId() + "\",\"sessionId\":\"" + value.sessionId()
            + "\",\"source\":\"" + value.source()
            + "\",\"iat\":" + value.issuedAt().toEpochMilli() + ",\"exp\":" + value.expiresAt().toEpochMilli() + "}";
    }

    private byte[] signWithKey(String value, String version)
    {
        String secret = environment.apply("LN_SESSION_RUNTIME_TOKEN_SECRET_" + version);
        if ((secret == null || secret.isBlank()) && "1".equals(version)) secret = environment.apply("LN_SESSION_RUNTIME_TOKEN_SECRET");
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE,
            "RUNTIME_AUTH_NOT_CONFIGURED", "Runtime token signing key is unavailable.");
        return mac(secret, value);
    }

    private static byte[] mac(String secret, String value)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        }
        catch (GeneralSecurityException error) { throw new IllegalStateException("Runtime token signature is unavailable", error); }
    }

    private static byte[] decodeSignature(String value)
    {
        try
        {
            byte[] decoded = DECODER.decode(value);
            if (!ENCODER.encodeToString(decoded).equals(value)) throw rejected();
            return decoded;
        }
        catch (IllegalArgumentException e) { throw rejected(); }
    }
    private static RuntimeProblem rejected() { return new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TOKEN_REJECTED", "The Session Token is invalid."); }

    public record V2Claims(String keyVersion, String tokenId, long accountId, long applicationId, long sessionId,
        String source, Instant issuedAt, Instant expiresAt) { }
}
