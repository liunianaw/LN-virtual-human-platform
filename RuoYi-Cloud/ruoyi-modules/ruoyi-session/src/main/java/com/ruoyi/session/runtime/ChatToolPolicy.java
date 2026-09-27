package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Runtime recheck of the same scalar-only Tool schema accepted at publication. */
@Component
public class ChatToolPolicy
{
    private final ObjectMapper json;
    public ChatToolPolicy(ObjectMapper json) { this.json = json; }

    public void validate(JsonNode schema, JsonNode value)
    {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())
            || !schema.path("properties").isObject() || schema.path("properties").size() > 20
            || value == null || !value.isObject() || value.size() > 20)
            throw new IllegalArgumentException("TOOL_SCHEMA_INVALID");
        value.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String type = schema.path("properties").path(key).path("type").asText();
            JsonNode field = entry.getValue();
            if (Set.of("externalUserId", "applicationId", "sessionId", "authorization").contains(key)
                || !switch (type) {
                    case "string" -> field.isTextual() && field.asText().length() <= 4096;
                    case "integer" -> field.isIntegralNumber();
                    case "number" -> field.isNumber();
                    case "boolean" -> field.isBoolean();
                    default -> false;
                }) throw new IllegalArgumentException("TOOL_ARGUMENT_INVALID");
        });
        JsonNode required = schema.path("required");
        if (required.isArray()) for (JsonNode key : required)
            if (!key.isTextual() || !value.has(key.asText()))
                throw new IllegalArgumentException("TOOL_ARGUMENT_INVALID");
    }

    public ObjectNode result(JsonNode schema, byte[] raw, long maxBytes) throws IOException
    {
        if (raw.length > maxBytes) throw new IOException("TOOL_RESULT_TOO_LARGE");
        JsonNode value = json.readTree(raw);
        if (value == null || !value.isObject()) throw new IOException("TOOL_RESULT_INVALID");
        ObjectNode filtered = json.createObjectNode();
        schema.path("properties").fieldNames().forEachRemaining(field -> {
            if (value.has(field)) filtered.set(field, value.get(field));
        });
        validate(schema, filtered);
        return filtered;
    }

    public ObjectNode frontend(ObjectNode result, List<String> allowed)
    {
        ObjectNode output = json.createObjectNode();
        for (String field : allowed) if (result.has(field)) output.set(field, result.get(field));
        return output;
    }
}
