package com.ruoyi.session.business;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.runtime.RuntimeProblem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionToolPolicyTest
{
    private final ObjectMapper json = new ObjectMapper();
    private final SessionToolPolicy policy = new SessionToolPolicy(json);
    @Test
    void identityCannotBeOverriddenAndOnlyPublicResultFieldsSurvive() throws Exception
    {
        var input = json.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}");
        assertThrows(RuntimeProblem.class, () -> policy.arguments(input,
            json.readTree("{\"query\":\"ok\",\"externalUserId\":\"other-user\"}")));
        assertThrows(RuntimeProblem.class, () -> policy.arguments(input, json.readTree("{}")));
        var output = json.readTree("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"},\"private\":{\"type\":\"string\"}}}");
        var result = policy.publicResult(output, List.of("summary"),
            "{\"summary\":\"visible\",\"private\":\"hidden\",\"extra\":\"discard\"}".getBytes(StandardCharsets.UTF_8), 1024);
        assertEquals(1, result.size());
        assertEquals("visible", result.path("summary").asText());
    }
}
