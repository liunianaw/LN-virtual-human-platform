package com.ruoyi.system.skill.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolPolicyTest
{
    private final ObjectMapper json = new ObjectMapper();
    private final ToolPolicy policy = new ToolPolicy(json);

    @Test void rejectsExecutableSchemaAndUnlistedFields() throws Exception
    {
        assertThrows(ServiceException.class, () -> policy.schema(json.readTree(
            "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"$ref\":\"https://evil.example/schema\"}")));
        var schema = policy.schema(json.readTree(
            "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"},\"private\":{\"type\":\"string\"}}}"));
        assertThrows(ServiceException.class, () -> policy.frontendFields(schema, List.of("unknown")));
        var trimmed = policy.publicResult(schema, List.of("summary"),
            "{\"summary\":\"ok\",\"extra\":\"secret\"}".getBytes(StandardCharsets.UTF_8), 100);
        assertEquals(1, trimmed.size());
        var result = policy.publicResult(schema, List.of("summary"),
            "{\"summary\":\"ok\",\"private\":\"hidden\"}".getBytes(StandardCharsets.UTF_8), 100);
        assertEquals("ok", result.path("summary").asText());
        assertEquals(1, result.size());
    }
}
