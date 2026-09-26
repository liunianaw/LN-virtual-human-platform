package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RuntimeTokenCodecTest
{
    private final RuntimeTokenCodec codec = new RuntimeTokenCodec(new ObjectMapper(), name ->
        name.equals("LN_SESSION_RUNTIME_TOKEN_SECRET") ? "0123456789abcdef0123456789abcdef" : null);

    @Test
    void v2RoundTripAndTampering()
    {
        Instant issued = Instant.ofEpochMilli(System.currentTimeMillis());
        RuntimeTokenCodec.V2Claims claims = new RuntimeTokenCodec.V2Claims("1",
            "0123456789abcdef0123456789abcdef", 11, 22, 33, 44, "BUSINESS_KEY", issued, issued.plusSeconds(900));
        String token = codec.encodeV2(claims);
        assertEquals(claims, codec.decodeV2("Bearer " + token));
        String[] parts = token.split("\\.");
        String changedPayload = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A")
            + parts[2].substring(1) + "." + parts[3];
        assertThrows(RuntimeProblem.class, () -> codec.decodeV2("Bearer " + changedPayload));
        String altered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        assertThrows(RuntimeProblem.class, () -> codec.decodeV2("Bearer " + altered));
        RuntimeTokenCodec.V2Claims expired = new RuntimeTokenCodec.V2Claims("1", claims.tokenId(), 11, 22, 33, 44,
            "BUSINESS_KEY", issued.minusSeconds(901), issued.minusSeconds(1));
        assertThrows(RuntimeProblem.class, () -> codec.decodeV2("Bearer " + codec.encodeV2(expired)));
    }

    @Test
    void legacyDebugV1RemainsReadableUntilOriginalExpiry()
    {
        RuntimeTokenCodec.Claims old = new RuntimeTokenCodec.Claims("old-grant", 11, 22, 33, 44,
            Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(600), "a".repeat(64),
            new VoiceRuntimeBinding(55, TtsProviderKind.OFFICIAL, "voice", null, 66L, 1L));
        assertEquals(old, codec.decode("Bearer " + codec.encode(old)));
    }
}
