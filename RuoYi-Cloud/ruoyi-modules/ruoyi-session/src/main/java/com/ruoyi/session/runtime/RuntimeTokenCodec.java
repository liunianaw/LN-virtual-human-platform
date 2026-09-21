package com.ruoyi.session.runtime;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Stateless HMAC-protected S token codec. The signing secret is deployment-only and never a browser credential. */
@Component
public class RuntimeTokenCodec
{
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode(Claims claims)
    {
        String payload = String.join("|", "v1", claims.tokenId(), Long.toString(claims.accountId()), Long.toString(claims.applicationId()),
                Long.toString(claims.sessionId()), Long.toString(claims.configVersionId()), Long.toString(claims.expiresAt().toEpochMilli()),
                claims.issuerConsoleRef(), Long.toString(claims.voice().voiceVersionId()), claims.voice().providerKind().name(), claims.voice().providerVoiceRef(),
                claims.voice().relayVersionRef() == null ? "" : claims.voice().relayVersionRef(),
                claims.voice().officialServiceId() == null ? "" : claims.voice().officialServiceId().toString(),
                claims.voice().officialServiceRevision() == null ? "" : claims.voice().officialServiceRevision().toString());
        String body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(sign(body));
    }

    public Claims decode(String authorization)
    {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw rejected();
        String[] parts = authorization.substring(7).split("\\.", -1);
        if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), decodeSignature(parts[1]))) throw rejected();
        try
        {
            String[] value = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (value.length != 14 || !"v1".equals(value[0])) throw rejected();
            Claims claims = new Claims(value[1], Long.parseLong(value[2]), Long.parseLong(value[3]), Long.parseLong(value[4]),
                    Long.parseLong(value[5]), Instant.ofEpochMilli(Long.parseLong(value[6])), value[7],
                    new VoiceRuntimeBinding(Long.parseLong(value[8]), TtsProviderKind.valueOf(value[9]), value[10], value[11].isBlank() ? null : value[11],
                        value[12].isBlank() ? null : Long.parseLong(value[12]), value[13].isBlank() ? null : Long.parseLong(value[13])));
            if (!claims.expiresAt().isAfter(Instant.now())) throw rejected();
            return claims;
        }
        catch (RuntimeProblem e) { throw e; }
        catch (Exception e) { throw rejected(); }
    }

    private byte[] sign(String body)
    {
        String secret = System.getenv("LN_SESSION_RUNTIME_TOKEN_SECRET");
        if (secret == null || secret.isBlank()) throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_AUTH_NOT_CONFIGURED", "Runtime token signing is not configured.");
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Runtime token signature is unavailable", e); }
    }

    private static byte[] decodeSignature(String value) { try { return DECODER.decode(value); } catch (IllegalArgumentException e) { throw rejected(); } }
    private static RuntimeProblem rejected() { return new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TOKEN_REJECTED", "The Session Token is invalid."); }

    public record Claims(String tokenId, long accountId, long applicationId, long sessionId, long configVersionId,
            Instant expiresAt, String issuerConsoleRef, VoiceRuntimeBinding voice) { }
}
