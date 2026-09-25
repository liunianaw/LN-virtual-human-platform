package com.ruoyi.system.relay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RelayCapabilityProbeTest
{
    private final RelayTarget target = mock(RelayTarget.class);
    private final RelayCapabilityProbe probe = new RelayCapabilityProbe(target, new ObjectMapper());

    @Test void handshakeSeparatesProtocolAndSelectedCapabilityFailures()
    {
        byte[] valid = json("{" +
            "\"data\":{\"protocol\":\"LN_RELAY\",\"protocolVersion\":\"1\","
            + "\"capabilities\":{\"llm\":true,\"asr\":true,\"cancel\":false},"
            + "\"asr\":{\"inputMimeTypes\":[\"audio/wav\"]}}}");
        assertTrue(probe.validateHandshake(valid, Map.of("llm", true, "asr", true)).ok());
        assertEquals("CAPABILITY", probe.validateHandshake(valid, Map.of("cancel", true)).errorCode());
        assertEquals("PROTOCOL", probe.validateHandshake(json("{\"protocol\":\"LN_RELAY\",\"protocolVersion\":\"2\"}"),
            Map.of("llm", true)).errorCode());
        assertEquals("PROTOCOL", probe.validateHandshake(json("not json"), Map.of("llm", true)).errorCode());
    }

    @Test void unsafeTargetNeverOpensConnection()
    {
        when(target.validate("https://relay.example.com")).thenThrow(new ServiceException("Unsafe", 400));
        assertEquals("TARGET", probe.check("https://relay.example.com", "token", Map.of("llm", true)).errorCode());
    }

    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
