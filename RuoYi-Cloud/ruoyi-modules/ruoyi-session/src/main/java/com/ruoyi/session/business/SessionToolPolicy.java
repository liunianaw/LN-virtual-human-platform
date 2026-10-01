package com.ruoyi.session.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.session.runtime.RuntimeProblem;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Runtime validation for the scalar-only JSON Schema subset accepted by System. */
@Component
public class SessionToolPolicy
{
    private static final Set<String> RESERVED = Set.of("externaluserid", "applicationid", "sessionid", "authorization");
    private final ObjectMapper json;
    public SessionToolPolicy(ObjectMapper json) { this.json = json; }

    public void arguments(JsonNode schema, JsonNode value)
    {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())
            || !schema.path("properties").isObject() || schema.path("properties").size() > 20
            || value == null || !value.isObject() || value.size() > 20)
            throw bad("TOOL_SCHEMA_INVALID");
        value.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            String type = schema.path("properties").path(key).path("type").asText();
            JsonNode field = entry.getValue();
            if (RESERVED.contains(key.toLowerCase(Locale.ROOT)) || !switch (type) {
                case "string" -> field.isTextual() && field.asText().length() <= 4096;
                case "integer" -> field.isIntegralNumber();
                case "number" -> field.isNumber();
                case "boolean" -> field.isBoolean();
                default -> false;
            }) throw bad("TOOL_ARGUMENT_INVALID");
        });
        JsonNode required = schema.path("required");
        if (required.isArray()) for (JsonNode key : required)
            if (!key.isTextual() || !value.has(key.asText())) throw bad("TOOL_ARGUMENT_INVALID");
    }

    public ObjectNode publicResult(JsonNode schema, List<String> fields, byte[] raw, long maxBytes)
    {
        if (raw == null || raw.length > maxBytes) throw bad("TOOL_RESULT_TOO_LARGE");
        try
        {
            JsonNode value = json.readTree(raw);
            if (value == null || !value.isObject()) throw bad("TOOL_RESULT_INVALID");
            ObjectNode schemaFields = json.createObjectNode();
            schema.path("properties").fieldNames().forEachRemaining(field -> {
                if (value.has(field)) schemaFields.set(field, value.get(field));
            });
            arguments(schema, schemaFields);
            if (fields == null || fields.size() > 20 || fields.size() != new HashSet<>(fields).size())
                throw bad("TOOL_SCHEMA_INVALID");
            ObjectNode result = json.createObjectNode();
            for (String field : fields)
            {
                if (!schema.path("properties").has(field)) throw bad("TOOL_SCHEMA_INVALID");
                if (schemaFields.has(field)) result.set(field, schemaFields.get(field));
            }
            return result;
        }
        catch (RuntimeProblem error) { throw error; }
        catch (IOException error) { throw bad("TOOL_RESULT_INVALID"); }
    }

    private static RuntimeProblem bad(String code)
    { return new RuntimeProblem(HttpStatus.BAD_REQUEST, code, code); }
}
