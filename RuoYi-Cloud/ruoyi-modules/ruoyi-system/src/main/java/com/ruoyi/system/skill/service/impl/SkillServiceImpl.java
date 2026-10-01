package com.ruoyi.system.skill.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.officialservice.domain.OfficialSecret;
import com.ruoyi.system.officialservice.service.OfficialSecretCrypto;
import com.ruoyi.system.skill.mapper.SkillMapper;
import com.ruoyi.system.skill.service.ISkillService;
import com.ruoyi.system.skill.service.ToolPolicy;
import com.ruoyi.system.skill.service.ToolTargetValidator;
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

/** Current Skill configuration, strict ownership, and per-call runtime authorization. */
@Service
public class SkillServiceImpl implements ISkillService
{
    private final SkillMapper mapper;
    private final ObjectMapper json;
    private final OfficialSecretCrypto crypto;
    private final ToolTargetValidator targets;
    private final ToolPolicy policy;

    public SkillServiceImpl(SkillMapper mapper, ObjectMapper json, OfficialSecretCrypto crypto,
        ToolTargetValidator targets, ToolPolicy policy)
    { this.mapper = mapper; this.json = json; this.crypto = crypto; this.targets = targets; this.policy = policy; }

    @Override public Map<String, Object> list(long accountId, int pageNum, int pageSize)
    {
        requireConsoleIdentity(accountId, false);
        if (accountId <= 0 || pageNum < 1 || pageSize < 1 || pageSize > 100) throw bad("分页参数无效");
        return Map.of("items", mapper.page(accountId, (pageNum - 1) * pageSize, pageSize).stream().map(this::view).toList(),
            "total", mapper.count(accountId), "pageNum", pageNum, "pageSize", pageSize);
    }

    @Override public Map<String, Object> candidates(long accountId)
    {
        requireConsoleIdentity(accountId, false);
        if (accountId <= 0) throw bad("账号无效");
        return Map.of("items", mapper.candidates(accountId).stream().map(this::publicConfig).toList());
    }

    @Override public Map<String, Object> listOfficial(long accountId, int pageNum, int pageSize)
    {
        requireConsoleIdentity(accountId, true);
        if (accountId <= 0 || pageNum < 1 || pageSize < 1 || pageSize > 100) throw bad("分页参数无效");
        return Map.of("items", mapper.pageOfficial(accountId, (pageNum - 1) * pageSize, pageSize).stream().map(this::view).toList(),
            "total", mapper.countOfficial(accountId), "pageNum", pageNum, "pageSize", pageSize);
    }

    @Override public Map<String, Object> detail(long accountId, long skillId)
    {
        requireConsoleIdentity(accountId, false);
        Map<String, Object> row = require(accountId, skillId);
        if (number(row, "accountId") != accountId || !"PRIVATE".equals(row.get("visibility")))
            throw missing();
        Map<String, Object> out = safeConfig(row);
        out.put("referenceCount", mapper.referenceCount(skillId));
        return out;
    }

    @Override public Map<String, Object> detailOfficial(long accountId, long skillId)
    {
        requireConsoleIdentity(accountId, true);
        Map<String, Object> row = mapper.officialSkill(accountId, skillId);
        if (row == null) throw missing();
        Map<String, Object> out = safeConfig(row);
        out.put("referenceCount", mapper.referenceCount(skillId));
        return out;
    }

    @Override @Transactional public Map<String, Object> create(long accountId, long actorId,
        SkillInput input, String key, boolean official)
    {
        requireConsoleIdentity(accountId, official);
        ValidConfig config = validate(input);
        String operation = official ? "skill:official:create" : "skill:create";
        byte[] hash = digest(write(Arrays.asList(input.name().trim(), trim(input.description()), config.safe())));
        Long replay = duplicate(accountId, operation, key, hash);
        if (replay != null) return replay(accountId, replay);
        long skillId = id();
        checkToolName(accountId, skillId, config);
        Long secretId = insertSecret(accountId, config);
        mapper.insertSkill(skillId, accountId, official ? "OFFICIAL" : "PRIVATE", input.name().trim(),
            trim(input.description()), config.skillType(), config.toolName(), config.instructions(),
            write(config.context()), config.toolUrl(), config.httpMethod(), nullable(config.inputSchema()),
            nullable(config.outputSchema()), secretId, config.requiresUserCredential(),
            nullable(config.identityBinding()), write(config.frontendFields()), config.timeoutMs(),
            config.maxResultBytes(), config.maxCallsPerSession(), config.importFormat(), hash);
        remember(accountId, operation, key, hash, skillId);
        return official ? detailOfficial(accountId, skillId) : detail(accountId, skillId);
    }

    @Override @Transactional public Map<String, Object> update(long accountId, long actorId, long skillId,
        SkillInput input, String ifMatch, String key, boolean official)
    {
        requireConsoleIdentity(accountId, official);
        ValidConfig config = validate(input);
        String operation = (official ? "skill:official:update:" : "skill:update:") + skillId;
        byte[] hash = digest(write(Arrays.asList(skillId, ifMatch == null ? "" : ifMatch,
            input.name().trim(), trim(input.description()), config.safe())));
        Long replay = duplicate(accountId, operation, key, hash);
        if (replay != null) return replay(accountId, replay);
        Map<String, Object> row = lock(accountId, skillId, official);
        long revision = revision(row, ifMatch);
        checkToolName(accountId, skillId, config);
        Long previousSecret = positive(row, "toolSecretId");
        Long secretId = config.accessToken() == null ? previousSecret : insertSecret(accountId, config);
        int changed = mapper.updateSkill(accountId, skillId, official ? "OFFICIAL" : "PRIVATE", revision,
            input.name().trim(), trim(input.description()), config.skillType(), config.toolName(),
            config.instructions(), write(config.context()), config.toolUrl(), config.httpMethod(),
            nullable(config.inputSchema()), nullable(config.outputSchema()), secretId,
            config.requiresUserCredential(), nullable(config.identityBinding()), write(config.frontendFields()),
            config.timeoutMs(), config.maxResultBytes(), config.maxCallsPerSession(), config.importFormat(), hash);
        if (changed != 1) throw stale();
        // Existing Session snapshots keep their encrypted secret reference until they end.
        remember(accountId, operation, key, hash, skillId);
        return official ? detailOfficial(accountId, skillId) : detail(accountId, skillId);
    }

    @Override @Transactional public Map<String, Object> changeStatus(long accountId, long skillId,
        StatusInput input, String ifMatch, String key, boolean official)
    {
        requireConsoleIdentity(accountId, official);
        if (input == null || !Set.of("PUBLISHED", "UNLISTED", "DISABLED").contains(input.status())
            || blank(input.reason()) || input.reason().length() > 500) throw bad("Skill 状态参数无效");
        String operation = (official ? "skill:official:status:" : "skill:status:") + skillId;
        byte[] hash = digest(operation + "|" + ifMatch + "|" + input.status() + "|" + input.reason());
        Long replay = duplicate(accountId, operation, key, hash);
        if (replay != null) return replay(accountId, replay);
        Map<String, Object> row = lock(accountId, skillId, official);
        long revision = revision(row, ifMatch);
        if ("PUBLISHED".equals(input.status()) && row.get("toolName") != null
            && mapper.toolNameCount(accountId, skillId, String.valueOf(row.get("toolName"))) != 0)
            throw conflict("Tool 名称已被可用 Skill 占用");
        if (mapper.setStatus(accountId, skillId, official ? "OFFICIAL" : "PRIVATE", input.status(), revision) != 1)
            throw stale();
        remember(accountId, operation, key, hash, skillId);
        return official ? detailOfficial(accountId, skillId) : detail(accountId, skillId);
    }

    @Override @Transactional public Map<String, Object> delete(long accountId, long skillId,
        String ifMatch, String key, boolean official)
    {
        requireConsoleIdentity(accountId, official);
        String operation = (official ? "skill:official:delete:" : "skill:delete:") + skillId;
        byte[] hash = digest(operation + "|" + ifMatch);
        Long replay = duplicate(accountId, operation, key, hash);
        if (replay != null) return replay(accountId, replay);
        Map<String, Object> row = lock(accountId, skillId, official);
        long revision = revision(row, ifMatch);
        if (mapper.referenceCount(skillId) != 0) throw conflict("Skill 已被 Application 或活动 Session 引用");
        if (mapper.setStatus(accountId, skillId, official ? "OFFICIAL" : "PRIVATE", "DELETED", revision) != 1)
            throw stale();
        mapper.disableSecrets(skillId);
        remember(accountId, operation, key, hash, skillId);
        return Map.of("skillId", Long.toString(skillId), "status", "DELETED");
    }

    @Override public Map<String, Object> checkConnection(long accountId, long skillId, boolean official)
    {
        requireConsoleIdentity(accountId, official);
        Map<String, Object> row = lock(accountId, skillId, official);
        if (!"HTTP_TOOL".equals(row.get("skillType"))) throw bad("仅 HTTP Tool 可检查连接");
        try
        {
            URI uri = targets.validate((String) row.get("toolUrl"));
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
            || binding.sessionId() <= 0 || binding.skillId() <= 0 || blank(binding.toolUrl()))
            throw bad("Skill 运行绑定无效");
        Map<String, Object> row = require(binding.accountId(), binding.skillId());
        if (!Set.of("PUBLISHED", "UNLISTED").contains(row.get("status"))
            || mapper.runtimeBindingCount(binding.accountId(), binding.applicationId(), binding.sessionId(),
                binding.skillId()) != 1) throw forbidden("Skill 当前不可用于此 Session");
        String address = targets.resolve(targets.validate(binding.toolUrl())).getHostAddress();
        OfficialSecret secret = binding.toolSecretId() == null ? null
            : mapper.secret(number(row, "accountId"), binding.toolSecretId());
        if (binding.toolSecretId() != null && secret == null) throw forbidden("Tool Secret 不可用");
        String token = secret == null ? null : crypto.decrypt(secret);
        return new ResolvedSkill(binding.skillId(), address, token);
    }

    private ValidConfig validate(SkillInput input)
    {
        if (input == null || blank(input.name()) || input.name().trim().length() > 100
            || input.description() != null && input.description().length() > 1000
            || !Set.of("PROMPT", "HTTP_TOOL").contains(input.skillType())
            || input.importFormat() != null && !"JSON".equals(input.importFormat()))
            throw bad("Skill 参数无效");
        JsonNode context = json.valueToTree(input.contextRequirements() == null ? Map.of() : input.contextRequirements());
        if (!context.isObject() || context.size() > 2) throw bad("Context 权限需求无效");
        context.fieldNames().forEachRemaining(name -> {
            if (!Set.of("page", "hybrid").contains(name) || !context.path(name).isBoolean())
                throw bad("Context 权限需求无效");
        });
        if ("PROMPT".equals(input.skillType()))
        {
            if (blank(input.instructions()) || input.instructions().length() > 32768
                || input.toolName() != null || input.toolUrl() != null || input.accessToken() != null
                || input.inputSchema() != null || input.outputSchema() != null || input.httpMethod() != null
                || input.requiresUserCredential() || input.identityBinding() != null
                || input.timeoutMs() != null || input.maxResultBytes() != null || input.maxCallsPerSession() != null
                || input.frontendFields() != null && !input.frontendFields().isEmpty())
                throw bad("Prompt Skill 字段无效");
            return new ValidConfig("PROMPT", null, input.instructions(), context, null, null, null, null,
                null, false, null, List.of(), null, null, null, input.importFormat());
        }
        if (input.instructions() != null || !Set.of("GET", "POST").contains(input.httpMethod())
            || blank(input.toolName()) || !input.toolName().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
            || input.timeoutMs() == null || input.timeoutMs() < 1000 || input.timeoutMs() > 30000
            || input.maxResultBytes() == null || input.maxResultBytes() < 1 || input.maxResultBytes() > 1048576
            || input.maxCallsPerSession() == null || input.maxCallsPerSession() < 1 || input.maxCallsPerSession() > 100)
            throw bad("HTTP Tool 字段或限制无效");
        URI target = targets.validate(input.toolUrl());
        JsonNode request = policy.schema(input.inputSchema()), response = policy.schema(input.outputSchema());
        List<String> frontend = input.frontendFields() == null ? List.of() : List.copyOf(input.frontendFields());
        policy.frontendFields(response, frontend);
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
            || input.accessToken().chars().anyMatch(ch -> ch <= 32 || ch >= 127)))
            throw bad("Tool Token 无效");
        return new ValidConfig("HTTP_TOOL", input.toolName(), null, context, target.toString(),
            input.httpMethod(), request, response, input.accessToken(), input.requiresUserCredential(),
            identity, frontend, input.timeoutMs(), input.maxResultBytes(), input.maxCallsPerSession(), input.importFormat());
    }

    private Long insertSecret(long accountId, ValidConfig config)
    {
        if (config.accessToken() == null) return null;
        long secretId = id();
        OfficialSecret secret = crypto.encrypt(secretId, config.accessToken());
        mapper.insertSecret(secret, accountId, config.toolName() + " Tool Token",
            config.accessToken().substring(config.accessToken().length() - 6));
        return secretId;
    }
    private void checkToolName(long accountId, long skillId, ValidConfig config)
    {
        if (config.toolName() != null && mapper.toolNameCount(accountId, skillId, config.toolName()) != 0)
            throw conflict("Tool 名称已被可用 Skill 占用");
    }
    private static void requireConsoleIdentity(long accountId, boolean official)
    {
        var login = com.ruoyi.common.security.utils.SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null || login.getUserid() != accountId)
            throw forbidden("后台账号身份无效");
        boolean admin = com.ruoyi.common.security.utils.SecurityUtils.isAdmin();
        if (admin != official || !admin && (login.getRoles() == null || !login.getRoles().contains("developer")))
            throw forbidden("当前角色不能操作此类 Skill");
    }

    private Map<String, Object> require(long accountId, long skillId)
    {
        if (accountId <= 0 || skillId <= 0) throw missing();
        Map<String, Object> row = mapper.skill(accountId, skillId);
        if (row == null) throw missing();
        return row;
    }
    private Map<String, Object> lock(long accountId, long skillId, boolean official)
    {
        requireConsoleIdentity(accountId, official);
        Map<String, Object> row = mapper.lockSkill(accountId, skillId, official ? "OFFICIAL" : "PRIVATE");
        if (row == null) throw missing();
        return row;
    }
    private long revision(Map<String, Object> row, String ifMatch)
    {
        if (blank(ifMatch)) throw new ServiceException("缺少 If-Match", 428);
        long current = number(row, "revision");
        if (!Long.toString(current).equals(ifMatch.trim())) throw new ServiceException("Skill 已变化", 412);
        return current;
    }
    private Long duplicate(long accountId, String operation, String key, byte[] hash)
    {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("Idempotency-Key 无效");
        if (mapper.lockAccount(accountId) == null) throw forbidden("账号不可用");
        Map<String, Object> row = mapper.idempotency(accountId, scope(operation), key);
        if (row == null) return null;
        if (!Arrays.equals(hash, (byte[]) row.get("requestHash"))) throw conflict("同一 Idempotency-Key 的参数不同");
        return number(row, "resourceId");
    }
    private void remember(long accountId, String operation, String key, byte[] hash, long skillId)
    { mapper.insertIdempotency(id(), accountId, scope(operation), key, hash, skillId); }
    private Map<String, Object> replay(long accountId, long skillId)
    {
        Map<String, Object> row = mapper.anySkill(accountId, skillId);
        if (row == null) throw conflict("Skill 幂等结果不存在");
        return "DELETED".equals(row.get("status")) ? view(row) :
            "OFFICIAL".equals(row.get("visibility")) ? detailOfficial(accountId, skillId) : detail(accountId, skillId);
    }
    private Map<String, Object> view(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("skillId", "visibility", "name", "description", "status", "skillType",
            "toolName", "revision", "createdAt", "updatedAt"))
            if (row.containsKey(key) && row.get(key) != null) out.put(key, row.get(key));
        for (String key : List.of("skillId", "revision"))
            if (out.get(key) instanceof Number value) out.put(key, Long.toString(value.longValue()));
        return out;
    }
    private Map<String, Object> publicConfig(Map<String, Object> row)
    {
        Map<String, Object> out = safeConfig(row);
        for (String key : List.of("toolUrl", "httpMethod", "tokenSuffix", "identityBinding", "requiresUserCredential"))
            out.remove(key);
        return out;
    }

    private Map<String, Object> safeConfig(Map<String, Object> row)
    {
        Map<String, Object> out = view(row);
        for (String key : List.of("instructions", "toolUrl", "httpMethod", "timeoutMs", "maxResultBytes",
            "maxCallsPerSession", "importFormat", "tokenSuffix"))
            if (row.get(key) != null) out.put(key, row.get(key));
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
        try { return value instanceof JsonNode ? value : json.readTree(String.valueOf(value)); }
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
    { Object value = row.get(key); return value instanceof Number n ? n.longValue() : value == null ? 0 : Long.parseLong(String.valueOf(value)); }
    private static Long positive(Map<String, Object> row, String key)
    { long value = number(row, key); return value > 0 ? value : null; }
    private long id() { long value = mapper.nextId(); if (value <= 0) throw conflict("Skill ID 生成失败"); return value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static ServiceException bad(String message) { return new ServiceException(message, 400); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, 403); }
    private static ServiceException conflict(String message) { return new ServiceException(message, 409); }
    private static ServiceException stale() { return new ServiceException("Skill 已变化，请刷新重试", 412); }
    private static ServiceException missing() { return new ServiceException("Skill 不存在", 404); }

    private record ValidConfig(String skillType, String toolName, String instructions, JsonNode context,
        String toolUrl, String httpMethod, JsonNode inputSchema, JsonNode outputSchema, String accessToken,
        boolean requiresUserCredential, JsonNode identityBinding, List<String> frontendFields,
        Integer timeoutMs, Long maxResultBytes, Integer maxCallsPerSession, String importFormat)
    {
        Object safe()
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("skillType", skillType); value.put("toolName", toolName); value.put("instructions", instructions);
            value.put("context", context); value.put("toolUrl", toolUrl); value.put("httpMethod", httpMethod);
            value.put("inputSchema", inputSchema); value.put("outputSchema", outputSchema);
            value.put("accessToken", accessToken == null ? null : "PRESENT"); value.put("requiresUserCredential", requiresUserCredential);
            value.put("identityBinding", identityBinding); value.put("frontendFields", frontendFields);
            value.put("timeoutMs", timeoutMs); value.put("maxResultBytes", maxResultBytes);
            value.put("maxCallsPerSession", maxCallsPerSession); value.put("importFormat", importFormat);
            return value;
        }
    }
}
