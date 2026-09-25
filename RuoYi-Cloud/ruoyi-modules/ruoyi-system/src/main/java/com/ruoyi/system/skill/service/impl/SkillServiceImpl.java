package com.ruoyi.system.skill.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.officialservice.domain.OfficialSecret;
import com.ruoyi.system.officialservice.service.OfficialSecretCrypto;
import com.ruoyi.system.relay.service.RelayTarget;
import com.ruoyi.system.skill.mapper.SkillMapper;
import com.ruoyi.system.skill.service.ISkillService;
import com.ruoyi.system.skill.service.ToolPolicy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Skill ownership, immutable versions, and current authorization for new actions. */
@Service
public class SkillServiceImpl implements ISkillService
{
    private final SkillMapper mapper;
    private final ObjectMapper json;
    private final OfficialSecretCrypto crypto;
    private final RelayTarget targets;
    private final ToolPolicy policy;
    public SkillServiceImpl(SkillMapper mapper, ObjectMapper json, OfficialSecretCrypto crypto,
        RelayTarget targets, ToolPolicy policy)
    { this.mapper = mapper; this.json = json; this.crypto = crypto; this.targets = targets; this.policy = policy; }

    @Override public Map<String, Object> list(long accountId, int pageNum, int pageSize)
    {
        if (accountId <= 0 || pageNum < 1 || pageSize < 1 || pageSize > 100) throw bad("分页参数无效");
        return Map.of("items", mapper.page(accountId, (pageNum - 1) * pageSize, pageSize).stream().map(this::view).toList(),
            "total", mapper.count(accountId), "pageNum", pageNum, "pageSize", pageSize);
    }

    @Override public Map<String, Object> candidates(long accountId)
    {
        if (accountId <= 0) throw bad("账号无效");
        return Map.of("items", mapper.candidates(accountId).stream().map(this::view).toList());
    }

    @Override public Map<String, Object> detail(long accountId, long skillId)
    {
        Map<String, Object> skill = require(accountId, skillId);
        Map<String, Object> out = view(skill);
        out.put("versions", mapper.versions(skillId).stream().map(this::versionView).toList());
        out.put("referenceCount", mapper.referenceCount(skillId));
        return out;
    }

    @Override @Transactional public Map<String, Object> create(long accountId, long actorId,
        CreateInput input, String key, boolean official)
    {
        if (accountId <= 0 || actorId <= 0 || input == null || blank(input.name())
            || input.name().trim().length() > 100 || input.description() != null && input.description().length() > 1000)
            throw bad("Skill 基本参数无效");
        if (official && (!SecurityUtils.isAdmin() || !Long.valueOf(actorId).equals(SecurityUtils.getUserId())))
            throw forbidden("仅管理员可创建官方 Skill");
        ValidVersion version = validate(input.version());
        String operation = official ? "skill:official:create" : "skill:create";
        byte[] hash = digest(write(List.of(input.name().trim(), input.description() == null ? "" : input.description(), safe(version))));
        Long replay = duplicate(accountId, operation, key, hash);
        if (replay != null) return replay(accountId, replay);
        long skillId = id();
        checkToolName(accountId, skillId, version);
        mapper.insertSkill(skillId, accountId, official ? "OFFICIAL" : "PRIVATE",
            input.name().trim(), trim(input.description()));
        insertVersion(accountId, actorId, skillId, 1, version);
        remember(accountId, operation, key, hash, skillId);
        return detail(accountId, skillId);
    }

    @Override @Transactional public Map<String, Object> addVersion(long accountId, long actorId,
        long skillId, VersionInput input, String ifMatch, String key)
    {
        ValidVersion version = validate(input);
        byte[] hash = digest(write(List.of(skillId, ifMatch == null ? "" : ifMatch, safe(version))));
        Long replay = duplicate(accountId, "skill:version:" + skillId, key, hash);
        if (replay != null) return replay(accountId, replay);
        Map<String, Object> skill = lock(accountId, skillId);
        long revision = revision(skill, ifMatch);
        if ("DISABLED".equals(skill.get("status"))) throw conflict("停用的 Skill 不能发布版本");
        checkToolName(accountId, skillId, version);
        insertVersion(accountId, actorId, skillId, mapper.nextVersionNo(skillId), version);
        remember(accountId, "skill:version:" + skillId, key, hash, skillId);
        return detail(accountId, skillId);
    }

    @Override @Transactional public Map<String, Object> changeStatus(long accountId, long skillId,
        StatusInput input, String ifMatch, String key)
    {
        if (input == null || !Set.of("PUBLISHED", "UNLISTED", "DISABLED").contains(input.status())
            || blank(input.reason()) || input.reason().length() > 500) throw bad("Skill 状态参数无效");
        byte[] hash = digest("status|" + skillId + "|" + ifMatch + "|" + input.status() + "|" + input.reason());
        Long replay = duplicate(accountId, "skill:status:" + skillId, key, hash);
        if (replay != null) return replay(accountId, replay);
        Map<String, Object> skill = lock(accountId, skillId);
        long revision = revision(skill, ifMatch);
        if ("PUBLISHED".equals(input.status()) && number(skill, "currentVersionId") <= 0)
            throw conflict("Skill 尚未发布版本");
        if ("PUBLISHED".equals(input.status()))
        {
            Map<String, Object> version = mapper.version(accountId, number(skill, "currentVersionId"));
            if (version == null) throw conflict("Skill 当前版本不存在");
            String toolName = (String) version.get("toolName");
            if (toolName != null && mapper.toolNameCount(accountId, skillId, toolName) != 0)
                throw conflict("Tool 名称已被可用 Skill 占用");
        }
        if (mapper.setStatus(accountId, skillId, input.status(), revision) != 1) throw stale();
        remember(accountId, "skill:status:" + skillId, key, hash, skillId);
        return detail(accountId, skillId);
    }

    @Override @Transactional public Map<String, Object> delete(long accountId, long skillId,
        String ifMatch, String key)
    {
        byte[] hash = digest("delete|" + skillId + "|" + ifMatch);
        Long replay = duplicate(accountId, "skill:delete:" + skillId, key, hash);
        if (replay != null) return replay(accountId, replay);
        Map<String, Object> skill = lock(accountId, skillId);
        long revision = revision(skill, ifMatch);
        if (mapper.referenceCount(skillId) != 0) throw conflict("Skill 已被应用或运行引用");
        if (mapper.setStatus(accountId, skillId, "DELETED", revision) != 1) throw stale();
        mapper.disableSecrets(skillId);
        remember(accountId, "skill:delete:" + skillId, key, hash, skillId);
        return Map.of("skillId", Long.toString(skillId), "status", "DELETED");
    }

    @Override public Map<String, Object> checkConnection(long accountId, long skillId)
    {
        Map<String, Object> skill = require(accountId, skillId);
        Map<String, Object> version = mapper.version(accountId, number(skill, "currentVersionId"));
        if (version == null || !"HTTP_TOOL".equals(version.get("skillType"))) throw bad("仅 HTTP Tool 可检查连接");
        // Connect to a freshly resolved public address and verify the original host TLS certificate.
        // Do not send a Tool request: GET and POST endpoints may both have side effects.
        try
        {
            URI uri = targets.validate((String) version.get("toolUrl"));
            try (Socket plain = new Socket())
            {
                plain.connect(new InetSocketAddress(targets.resolve(uri), 443), 5000);
                plain.setSoTimeout(5000);
                try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, uri.getHost(), 443, true))
                {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                }
            }
            return Map.of("success", true, "checked", "TARGET_TLS_ONLY");
        }
        catch (ServiceException error) { return Map.of("success", false, "errorCode", "TARGET"); }
        catch (javax.net.ssl.SSLException error) { return Map.of("success", false, "errorCode", "TLS"); }
        catch (Exception error) { return Map.of("success", false, "errorCode", "NETWORK"); }
    }

    @Override public ResolvedSkill resolve(RuntimeBinding binding)
    {
        if (binding == null || binding.accountId() <= 0 || binding.applicationId() <= 0
            || binding.sessionId() <= 0 || binding.skillVersionId() <= 0) throw bad("Skill 运行绑定无效");
        Map<String, Object> version = mapper.version(binding.accountId(), binding.skillVersionId());
        if (version == null) throw forbidden("Skill 版本不可访问");
        Map<String, Object> skill = require(binding.accountId(), number(version, "skillId"));
        if ("DISABLED".equals(skill.get("status")) || !Set.of("PUBLISHED", "UNLISTED").contains(skill.get("status"))
            || mapper.runtimeBindingCount(binding.accountId(), binding.applicationId(), binding.sessionId(),
                binding.skillVersionId()) != 1) throw forbidden("Skill 当前不可用于此 Session");
        boolean tool = "HTTP_TOOL".equals(version.get("skillType"));
        String url = tool ? (String) version.get("toolUrl") : null;
        String address = tool ? targets.resolve(targets.validate(url)).getHostAddress() : null;
        Long secretId = number(version, "toolSecretId");
        String token = tool && secretId > 0 ? crypto.decrypt(mapper.secret(number(version, "accountId"), secretId)) : null;
        return new ResolvedSkill((String) version.get("skillType"), (String) version.get("instructions"),
            parse(version.get("contextRequirements")), (String) version.get("toolName"), url, address,
            (String) version.get("httpMethod"), parse(version.get("inputSchema")), parse(version.get("outputSchema")),
            strings(version.get("frontendFields")), (int) number(version, "timeoutMs"),
            number(version, "maxResultBytes"), (int) number(version, "maxCallsPerTurn"), token,
            number(version, "requiresUserCredential") == 1, parse(version.get("identityBinding")));
    }

    private ValidVersion validate(VersionInput input)
    {
        if (input == null || !Set.of("PROMPT", "HTTP_TOOL").contains(input.skillType())
            || input.importFormat() != null && !"JSON".equals(input.importFormat())) throw bad("Skill 版本类型或导入格式无效");
        JsonNode context = json.valueToTree(input.contextRequirements() == null ? Map.of() : input.contextRequirements());
        if (!context.isObject() || context.size() > 3) throw bad("Context 权限需求无效");
        context.fieldNames().forEachRemaining(key -> { if (!Set.of("element", "page", "hybrid").contains(key)
            || !context.path(key).isBoolean()) throw bad("Context 权限需求无效"); });
        if ("PROMPT".equals(input.skillType()))
        {
            if (blank(input.instructions()) || input.instructions().length() > 32768
                || input.toolName() != null || input.toolUrl() != null || input.accessToken() != null
                || input.inputSchema() != null || input.outputSchema() != null || input.httpMethod() != null
                || input.requiresUserCredential() || input.identityBinding() != null
                || input.timeoutMs() != null || input.maxResultBytes() != null || input.maxCallsPerTurn() != null
                || input.frontendFields() != null && !input.frontendFields().isEmpty())
                throw bad("Prompt Skill 字段无效");
            return new ValidVersion(input.skillType(), null, input.instructions(), context, null, null,
                null, null, null, false, null, List.of(), null, null, null, input.importFormat());
        }
        if (input.instructions() != null || !"GET".equals(input.httpMethod()) && !"POST".equals(input.httpMethod())
            || blank(input.toolName()) || !input.toolName().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
            || input.timeoutMs() == null || input.timeoutMs() < 1000 || input.timeoutMs() > 30000
            || input.maxResultBytes() == null || input.maxResultBytes() < 1 || input.maxResultBytes() > 1048576
            || input.maxCallsPerTurn() == null || input.maxCallsPerTurn() < 1 || input.maxCallsPerTurn() > 10)
            throw bad("HTTP Tool 字段或限制无效");
        URI target = targets.validate(input.toolUrl());
        JsonNode request = policy.schema(input.inputSchema()), response = policy.schema(input.outputSchema());
        List<String> frontendFields = input.frontendFields() == null ? List.of() : input.frontendFields();
        policy.frontendFields(response, frontendFields);
        JsonNode identity = json.valueToTree(input.identityBinding() == null ? Map.of() : input.identityBinding());
        if (!identity.isObject() || identity.size() > 3) throw bad("Tool 身份绑定无效");
        identity.fields().forEachRemaining(entry -> {
            if (!Set.of("externalUserId", "applicationId", "sessionId").contains(entry.getKey())
                || !"HEADER".equals(entry.getValue().asText())) throw bad("Tool 身份绑定无效");
        });
        if (input.requiresUserCredential() && !"HEADER".equals(identity.path("externalUserId").asText()))
            throw bad("业务用户凭证须绑定服务端身份头");
        if (input.accessToken() != null && (input.accessToken().length() < 32 || input.accessToken().length() > 4096
            || !StandardCharsets.US_ASCII.newEncoder().canEncode(input.accessToken())
            || input.accessToken().chars().anyMatch(ch -> ch <= 32 || ch >= 127))) throw bad("Tool Token 无效");
        return new ValidVersion("HTTP_TOOL", input.toolName(), null, context, target.toString(),
            input.httpMethod(), request, response, input.accessToken(), input.requiresUserCredential(),
            identity, frontendFields, input.timeoutMs(), input.maxResultBytes(),
            input.maxCallsPerTurn(), input.importFormat());
    }

    private void insertVersion(long accountId, long actorId, long skillId, int number, ValidVersion input)
    {
        Long secretId = input.accessToken() == null ? null : id();
        if (secretId != null)
        {
            OfficialSecret secret = crypto.encrypt(secretId, input.accessToken());
            mapper.insertSecret(secret, accountId, input.toolName() + " Tool Token",
                input.accessToken().substring(input.accessToken().length() - 6));
        }
        long versionId = id();
        mapper.insertVersion(versionId, accountId, skillId, number, input.skillType(), input.toolName(),
            input.instructions(), write(input.context()), input.toolUrl(), input.httpMethod(),
            nullable(input.inputSchema()), nullable(input.outputSchema()), secretId,
            input.requiresUserCredential(), nullable(input.identityBinding()), write(input.frontendFields()),
            input.timeoutMs(), input.maxResultBytes(), input.maxCallsPerTurn(), input.importFormat(),
            digest(write(safe(input))), actorId);
        Map<String, Object> current = mapper.skill(accountId, skillId);
        if (current == null || mapper.publish(accountId, skillId, versionId, number(current, "revision")) != 1)
            throw stale();
    }

    private void checkToolName(long accountId, long skillId, ValidVersion version)
    { if (version.toolName() != null && mapper.toolNameCount(accountId, skillId, version.toolName()) != 0)
        throw conflict("Tool 名称已被可用 Skill 占用"); }
    private Object safe(ValidVersion version)
    { return List.of(version.safe(), version.accessToken() == null ? "" :
        HexFormat.of().formatHex(crypto.keyedDigest(version.accessToken()))); }
    private Map<String, Object> require(long accountId, long skillId)
    {
        if (accountId <= 0 || skillId <= 0) throw missing();
        Map<String, Object> row = mapper.skill(accountId, skillId);
        if (row == null) throw missing();
        return row;
    }
    private Map<String, Object> lock(long accountId, long skillId)
    {
        Map<String, Object> row = mapper.lockSkill(accountId, skillId);
        if (row == null) throw missing();
        return row;
    }
    private long revision(Map<String, Object> row, String ifMatch)
    {
        if (blank(ifMatch)) throw new ServiceException("缺少 If-Match", 428);
        long value = number(row, "revision");
        if (!Long.toString(value).equals(ifMatch)) throw new ServiceException("Skill 状态已变化", 412);
        return value;
    }
    private Long duplicate(long accountId, String operation, String key, byte[] hash)
    {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("Idempotency-Key 无效");
        if (mapper.lockAccount(accountId) == null) throw forbidden("账号不可用");
        Map<String, Object> row = mapper.idempotency(accountId, scope(operation), key);
        if (row == null) return null;
        if (!Arrays.equals(hash, (byte[]) row.get("requestHash"))) throw conflict("同一 Idempotency-Key 的参数不同");
        if (!"SUCCEEDED".equals(row.get("status"))) throw conflict("Skill 操作处理中");
        return number(row, "resourceId");
    }
    private void remember(long accountId, String operation, String key, byte[] hash, long skillId)
    { mapper.insertIdempotency(id(), accountId, scope(operation), key, hash, skillId); }
    private Map<String, Object> replay(long accountId, long skillId)
    {
        Map<String, Object> row = mapper.anySkill(accountId, skillId);
        if (row == null) throw conflict("Skill 幂等结果不存在");
        return "DELETED".equals(row.get("status")) ? view(row) : detail(accountId, skillId);
    }
    private Map<String, Object> view(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("skillId", "visibility", "name", "description", "status",
            "currentVersionId", "revision", "createdAt", "updatedAt"))
            if (row.containsKey(key)) out.put(key, row.get(key));
        for (String key : List.of("skillId", "currentVersionId", "revision"))
            if (out.get(key) instanceof Number number) out.put(key, Long.toString(number.longValue()));
        return out;
    }
    private Map<String, Object> versionView(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("versionNo", "skillType", "toolName", "instructions", "toolUrl", "httpMethod",
            "timeoutMs", "maxResultBytes", "maxCallsPerTurn", "importFormat", "tokenSuffix", "createdAt"))
            if (row.containsKey(key)) out.put(key, row.get(key));
        out.put("versionId", Long.toString(number(row, "versionId")));
        out.put("contextRequirements", parse(row.get("contextRequirements")));
        out.put("inputSchema", parse(row.get("inputSchema")));
        out.put("outputSchema", parse(row.get("outputSchema")));
        out.put("frontendFields", strings(row.get("frontendFields")));
        out.put("identityBinding", parse(row.get("identityBinding")));
        out.put("requiresUserCredential", number(row, "requiresUserCredential") == 1);
        return out;
    }
    private Object parse(Object value)
    {
        if (value == null) return null;
        try { return json.readTree(String.valueOf(value)); }
        catch (Exception error) { throw conflict("Skill JSON 数据损坏"); }
    }
    private List<String> strings(Object value)
    {
        if (value == null) return List.of();
        try { return json.readValue(String.valueOf(value), json.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception error) { throw conflict("Skill 字段白名单损坏"); }
    }
    private String nullable(Object value) { return value == null ? null : write(value); }
    private String write(Object value)
    { try { return json.writeValueAsString(value); } catch (Exception error) { throw bad("Skill 配置无法编码"); } }
    private static byte[] digest(String value)
    { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
      catch (Exception error) { throw new IllegalStateException(error); } }
    private static String scope(String operation) { return HexFormat.of().formatHex(digest(operation)); }
    private static long number(Map<String, Object> row, String key)
    { Object value = row.get(key); return value instanceof Number number ? number.longValue() : 0; }
    private long id() { long value = mapper.nextId(); if (value <= 0) throw conflict("Skill ID 生成失败"); return value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static ServiceException bad(String message) { return new ServiceException(message, 400); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, 403); }
    private static ServiceException conflict(String message) { return new ServiceException(message, 409); }
    private static ServiceException stale() { return conflict("Skill 状态已变化，请刷新重试"); }
    private static ServiceException missing() { return new ServiceException("Skill 不存在", 404); }

    private record ValidVersion(String skillType, String toolName, String instructions, JsonNode context,
        String toolUrl, String httpMethod, JsonNode inputSchema, JsonNode outputSchema, String accessToken,
        boolean requiresUserCredential, JsonNode identityBinding, List<String> frontendFields,
        Integer timeoutMs, Long maxResultBytes, Integer maxCallsPerTurn, String importFormat)
    {
        Object safe() { return List.of(skillType, toolName == null ? "" : toolName,
            instructions == null ? "" : instructions, context, toolUrl == null ? "" : toolUrl,
            httpMethod == null ? "" : httpMethod, inputSchema == null ? "" : inputSchema,
            outputSchema == null ? "" : outputSchema,
            accessToken == null ? "" : "PRESENT", requiresUserCredential,
            identityBinding == null ? "" : identityBinding, frontendFields,
            timeoutMs == null ? 0 : timeoutMs, maxResultBytes == null ? 0 : maxResultBytes,
            maxCallsPerTurn == null ? 0 : maxCallsPerTurn, importFormat == null ? "" : importFormat); }
    }
}
