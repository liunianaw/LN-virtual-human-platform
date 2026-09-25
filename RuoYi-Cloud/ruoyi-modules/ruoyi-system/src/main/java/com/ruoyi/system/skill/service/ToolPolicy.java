package com.ruoyi.system.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** The only JSON Schema subset accepted for Tool input and public output. */
@Component
public class ToolPolicy
{
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");
    private static final Set<String> RESERVED = Set.of("externaluserid", "applicationid", "sessionid", "authorization");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private final ObjectMapper json;
    public ToolPolicy(ObjectMapper json) { this.json = json; }

    public JsonNode schema(Object raw)
    {
        JsonNode node = json.valueToTree(raw);
        if (!node.isObject() || node.size() < 2 || node.size() > 3
            || !"object".equals(node.path("type").asText()) || !node.path("properties").isObject()
            || node.path("properties").size() > 20 || node.path("properties").size() == 0)
            throw bad("Tool Schema 仅支持有限对象字段");
        for (String key : (Iterable<String>) () -> node.fieldNames())
            if (!Set.of("type", "properties", "required").contains(key)) throw bad("Tool Schema 含不支持的关键字");
        node.path("properties").fields().forEachRemaining(entry -> {
            JsonNode field = entry.getValue();
            if (!FIELD.matcher(entry.getKey()).matches() || RESERVED.contains(entry.getKey().toLowerCase())
                || !field.isObject() || field.size() != 1
                || !TYPES.contains(field.path("type").asText())) throw bad("Tool 字段定义无效");
        });
        JsonNode required = node.path("required");
        if (!required.isMissingNode())
        {
            if (!required.isArray() || required.size() > 20) throw bad("Tool 必填字段无效");
            Set<String> seen = new HashSet<>();
            for (JsonNode field : required)
                if (!field.isTextual() || !seen.add(field.asText()) || !node.path("properties").has(field.asText()))
                    throw bad("Tool 必填字段无效");
        }
        return node;
    }

    public void arguments(JsonNode schema, JsonNode value)
    {
        if (value == null || !value.isObject()) throw bad("Tool 参数须为对象");
        value.fields().forEachRemaining(entry -> {
            String type = schema.path("properties").path(entry.getKey()).path("type").asText();
            JsonNode item = entry.getValue();
            if (!switch (type) {
                case "string" -> item.isTextual() && item.asText().length() <= 4096;
                case "integer" -> item.isIntegralNumber();
                case "number" -> item.isNumber();
                case "boolean" -> item.isBoolean();
                default -> false;
            }) throw bad("Tool 参数类型或字段无效");
        });
        JsonNode required = schema.path("required");
        if (required.isArray()) for (JsonNode field : required)
            if (!value.has(field.asText())) throw bad("Tool 缺少必填参数");
    }

    public JsonNode publicResult(JsonNode schema, List<String> fields, byte[] response, long maxBytes)
    {
        if (response == null || response.length > maxBytes) throw bad("Tool 结果超过上限");
        try
        {
            JsonNode value = json.readTree(response);
            if (value == null || !value.isObject()) throw bad("Tool 结果须为 JSON 对象");
            var allowed = json.createObjectNode();
            schema.path("properties").fieldNames().forEachRemaining(field -> {
                if (value.has(field)) allowed.set(field, value.get(field));
            });
            arguments(schema, allowed);
            var out = json.createObjectNode();
            for (String field : fields) if (allowed.has(field)) out.set(field, allowed.get(field));
            return out;
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw bad("Tool 结果不是有效 JSON"); }
    }

    public void frontendFields(JsonNode output, List<String> fields)
    {
        if (fields == null || fields.size() > 20 || fields.size() != new HashSet<>(fields).size())
            throw bad("Tool 前端字段无效");
        for (String field : fields) if (!output.path("properties").has(field)) throw bad("Tool 前端字段不在结果 Schema 中");
    }
    private static ServiceException bad(String message) { return new ServiceException(message, 400); }
}
