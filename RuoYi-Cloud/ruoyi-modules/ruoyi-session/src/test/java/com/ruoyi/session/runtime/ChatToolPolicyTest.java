package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatToolPolicyTest
{
    private final ObjectMapper json = new ObjectMapper();
    private final ChatToolPolicy policy = new ChatToolPolicy(json);

    @Test
    void rejectsModelIdentityFieldsAndReturnsOnlyDeclaredPublicFields() throws Exception
    {
        var input = json.readTree("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}");
        assertThrows(IllegalArgumentException.class, () ->
            policy.validate(input, json.readTree("{\"query\":\"ok\",\"externalUserId\":\"other-user\"}")));
        var output = json.readTree("{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"},\"private\":{\"type\":\"string\"}}}");
        var result = policy.result(output,
            "{\"summary\":\"visible\",\"private\":\"model-only\",\"extra\":\"discard\"}".getBytes(StandardCharsets.UTF_8), 1024);
        assertEquals(2, result.size());
        var publicFields = policy.frontend(result, List.of("summary"));
        assertEquals("visible", publicFields.path("summary").asText());
        assertEquals(1, publicFields.size());
    }
}
