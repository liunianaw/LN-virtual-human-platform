package com.ruoyi.system.relay.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.officialservice.domain.OfficialSecret;
import com.ruoyi.system.officialservice.service.OfficialSecretCrypto;
import com.ruoyi.system.relay.mapper.RelayMapper;
import com.ruoyi.system.relay.service.IRelayService;
import com.ruoyi.system.relay.service.RelayCapabilityProbe;
import com.ruoyi.system.relay.service.RelayTarget;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns Relay versions, grants, and the current authorization consulted before every new operation. */
@Service
public class RelayServiceImpl implements IRelayService
{
    private static final Map<String, String> PATHS = Map.of("capabilities", "/capabilities",
        "llm", "/chat/completions", "asr", "/audio/transcriptions",
        "cancel", "/requests/{requestId}/cancel");
    private static final Set<String> CAPABILITIES = Set.of("llm", "asr", "image", "tool", "cancel");
    private final RelayMapper mapper;
    private final OfficialSecretCrypto crypto;
    private final RelayTarget targets;
    private final RelayCapabilityProbe probe;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public RelayServiceImpl(RelayMapper mapper, OfficialSecretCrypto crypto, RelayTarget targets,
        RelayCapabilityProbe probe, ObjectMapper json, PlatformTransactionManager transactionManager)
    { this.mapper = mapper; this.crypto = crypto; this.targets = targets; this.probe = probe;
      this.json = json; this.transactions = new TransactionTemplate(transactionManager); }

    @Override public Map<String, Object> list(long accountId, int pageNum, int pageSize)
    {
        if (accountId <= 0 || pageNum < 1 || pageSize < 1 || pageSize > 100) throw bad("分页参数无效");
        return Map.of("items", mapper.page(accountId, (pageNum - 1) * pageSize, pageSize).stream().map(this::view).toList(),
            "total", mapper.count(accountId), "pageNum", pageNum, "pageSize", pageSize);
    }

    @Override public Map<String, Object> detail(long accountId, long relayId)
    {
        Map<String, Object> item = view(require(accountId, relayId));
        item.put("versions", mapper.versions(accountId, relayId).stream().map(this::versionView).toList());
        item.put("grants", mapper.grants(accountId, relayId).stream().map(this::grantView).toList());
        return item;
    }

    @Override @Transactional public Map<String, Object> create(long accountId, long actorId, CreateInput input, String key)
    {
        if (accountId <= 0 || actorId <= 0 || input == null || blank(input.name()) || input.name().trim().length() > 100
            || input.description() != null && input.description().length() > 500
            || !("ALL_ACCOUNT_APPS".equals(input.grantMode()) || "EXPLICIT_APPS".equals(input.grantMode()))) throw bad("Relay 基本参数无效");
        requireToken(input.accessToken());
        ValidVersion version = validateVersion(input.version());
        byte[] hash = digest("create|" + input.name().trim() + "|" + trim(input.description()) + "|"
            + input.grantMode() + "|" + version + "|" + HexFormat.of().formatHex(crypto.keyedDigest(input.accessToken())));
        Long duplicate = duplicate(accountId, "relay:create", key, hash);
        if (duplicate != null) return replay(accountId, duplicate);
        long secretId = id();
        long relayId = id();
        OfficialSecret secret = crypto.encrypt(secretId, input.accessToken());
        mapper.insertSecret(secret, accountId, input.name().trim() + " Relay Token", suffix(input.accessToken()));
        mapper.insertService(relayId, accountId, input.name().trim(), trim(input.description()), secretId, input.grantMode());
        insertVersion(accountId, actorId, relayId, 1, version);
        remember(accountId, "relay:create", key, hash, relayId);
        return detail(accountId, relayId);
    }

    @Override @Transactional public Map<String, Object> addVersion(long accountId, long actorId, long relayId,
        VersionInput input, String ifMatch, String key)
    {
        ValidVersion version = validateVersion(input);
        byte[] hash = digest("version|" + relayId + "|" + ifMatch + "|" + version);
        Long duplicate = duplicate(accountId, "relay:version:" + relayId, key, hash);
        if (duplicate != null) return replay(accountId, duplicate);
        Map<String, Object> current = lock(accountId, relayId);
        long epoch = epoch(current, ifMatch);
        int number = mapper.nextVersionNo(relayId);
        insertVersion(accountId, actorId, relayId, number, version);
        remember(accountId, "relay:version:" + relayId, key, hash, relayId);
        return detail(accountId, relayId);
    }

    @Override @Transactional public Map<String, Object> replaceGrants(long accountId, long relayId,
        GrantsInput input, String ifMatch, String key)
    {
        if (input == null || !("ALL_ACCOUNT_APPS".equals(input.grantMode()) || "EXPLICIT_APPS".equals(input.grantMode()))
            || input.grants() == null || input.grants().size() > 200
            || "ALL_ACCOUNT_APPS".equals(input.grantMode()) && !input.grants().isEmpty()) throw bad("Relay 授权无效");
        byte[] hash = digest("grants|" + relayId + "|" + ifMatch + "|" + write(input));
        Long duplicate = duplicate(accountId, "relay:grants:" + relayId, key, hash);
        if (duplicate != null) return replay(accountId, duplicate);
        Map<String, Object> current = lock(accountId, relayId);
        long epoch = epoch(current, ifMatch);
        Map<String, Object> version = currentVersion(accountId, current);
        Map<String, Boolean> capabilities = booleans(version.get("capabilities"));
        Set<Long> seen = new HashSet<>();
        for (GrantInput grant : input.grants())
        {
            if (grant == null || grant.applicationId() <= 0 || !seen.add(grant.applicationId())
                || mapper.countOwnedApplication(accountId, grant.applicationId()) != 1
                || invalidScopes(grant.scopes(), capabilities)) throw bad("Relay 应用授权无效");
        }
        if (mapper.setGrantMode(accountId, relayId, input.grantMode(), epoch) != 1) throw stale();
        mapper.revokeGrants(accountId, relayId);
        for (GrantInput grant : input.grants())
            mapper.upsertGrant(id(), accountId, relayId, grant.applicationId(), write(grant.scopes()));
        remember(accountId, "relay:grants:" + relayId, key, hash, relayId);
        return detail(accountId, relayId);
    }

    @Override @Transactional public Map<String, Object> rotateToken(long accountId, long relayId,
        TokenInput input, String ifMatch, String key)
    {
        if (input == null) throw bad("Relay Token 无效");
        requireToken(input.accessToken());
        byte[] hash = digest("token|" + relayId + "|" + ifMatch + "|"
            + HexFormat.of().formatHex(crypto.keyedDigest(input.accessToken())));
        Long duplicate = duplicate(accountId, "relay:token:" + relayId, key, hash);
        if (duplicate != null) return replay(accountId, duplicate);
        Map<String, Object> current = lock(accountId, relayId);
        long epoch = epoch(current, ifMatch);
        if (number(current, "adminDisabled") != 0) throw forbidden("管理员已禁用 Relay");
        long secretId = number(current, "accessSecretId");
        OfficialSecret secret = crypto.encrypt(secretId, input.accessToken());
        if (mapper.updateSecret(secret, accountId, suffix(input.accessToken())) != 1
            || mapper.resetTest(accountId, relayId, epoch) != 1) throw stale();
        remember(accountId, "relay:token:" + relayId, key, hash, relayId);
        return detail(accountId, relayId);
    }

    @Override @Transactional public Map<String, Object> changeStatus(long accountId, long relayId,
        StatusInput input, String ifMatch, String key)
    {
        if (input == null || !("ACTIVE".equals(input.status()) || "DISABLED".equals(input.status()))
            || blank(input.reason()) || input.reason().length() > 500) throw bad("Relay 状态参数无效");
        byte[] hash = digest("status|" + relayId + "|" + ifMatch + "|" + input.status() + "|" + input.reason().trim());
        Long duplicate = duplicate(accountId, "relay:status:" + relayId, key, hash);
        if (duplicate != null) return replay(accountId, duplicate);
        Map<String, Object> current = lock(accountId, relayId);
        long epoch = epoch(current, ifMatch);
        if ("ACTIVE".equals(input.status()))
        {
            if (number(current, "adminDisabled") != 0) throw forbidden("管理员已禁用 Relay");
            if (!"SUCCESS".equals(current.get("lastTestStatus"))
                || number(current, "lastTestVersionId") != number(current, "currentVersionId"))
                throw conflict("当前版本须先通过连接测试");
        }
        if (mapper.setStatus(accountId, relayId, input.status(), epoch) != 1) throw stale();
        mapper.insertStatusEvent(id(), accountId, UUID.randomUUID().toString().replace("-", ""), relayId,
            write(Map.of("relayId", Long.toString(relayId), "status", input.status(), "reason", input.reason().trim())));
        remember(accountId, "relay:status:" + relayId, key, hash, relayId);
        return detail(accountId, relayId);
    }

    @Override @Transactional public Map<String, Object> delete(long accountId, long relayId, String ifMatch, String key)
    {
        byte[] hash = digest("delete|" + relayId + "|" + ifMatch);
        Long duplicate = duplicate(accountId, "relay:delete:" + relayId, key, hash);
        if (duplicate != null) return replay(accountId, duplicate);
        Map<String, Object> current = lock(accountId, relayId);
        long epoch = epoch(current, ifMatch);
        if (mapper.countReferences(relayId) != 0) throw conflict("Relay 已被应用或声音引用");
        if (mapper.setStatus(accountId, relayId, "DELETED", epoch) != 1) throw stale();
        mapper.disableSecret(accountId, number(current, "accessSecretId"));
        mapper.insertStatusEvent(id(), accountId, UUID.randomUUID().toString().replace("-", ""), relayId,
            write(Map.of("relayId", Long.toString(relayId), "status", "DELETED", "reason", "OWNER_DELETE")));
        remember(accountId, "relay:delete:" + relayId, key, hash, relayId);
        return Map.of("relayId", Long.toString(relayId), "status", "DELETED");
    }

    @Override public Map<String, Object> testConnection(long accountId, long relayId, String key)
    {
        Map<String, Object> current = require(accountId, relayId);
        Map<String, Object> version = currentVersion(accountId, current);
        long epoch = number(current, "authEpoch"), versionId = number(current, "currentVersionId");
        if (number(current, "adminDisabled") != 0) throw forbidden("管理员已禁用 Relay");
        if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("Idempotency-Key 无效");
        String operation = "relay:test:" + relayId;
        byte[] hash = digest("test|" + relayId + "|" + versionId + "|" + epoch);
        Map<String, Object> existing = mapper.idempotency(accountId, scope(operation), key);
        if (existing != null)
        {
            if (!Arrays.equals(hash, (byte[]) existing.get("requestHash"))) throw conflict("同一 Idempotency-Key 的请求参数不同");
            if (!"SUCCEEDED".equals(existing.get("status")) || number(existing, "resourceId") != relayId)
                throw conflict("Relay 连接测试仍在处理中");
            return testView(require(accountId, relayId));
        }
        String token = crypto.decrypt(mapper.secret(accountId, number(current, "accessSecretId")));
        RelayCapabilityProbe.Result result = probe.check((String) version.get("baseUrl"), token,
            booleans(version.get("capabilities")));
        return transactions.execute(status -> {
            Long duplicate = duplicate(accountId, operation, key, hash);
            if (duplicate != null) return testView(require(accountId, relayId));
            if (mapper.saveTest(accountId, relayId, versionId, epoch, result.ok() ? "SUCCESS" : "FAILED", result.errorCode()) != 1)
                throw stale();
            remember(accountId, operation, key, hash, relayId);
            return testView(require(accountId, relayId));
        });
    }

    @Override @Transactional public Map<String, Object> changeAdminDisabled(long administratorId, long relayId,
        boolean disabled, String reason)
    {
        if (administratorId <= 0 || !SecurityUtils.isAdmin() || !Long.valueOf(administratorId).equals(SecurityUtils.getUserId())
            || blank(reason) || reason.length() > 500) throw forbidden("仅管理员可限制 Relay");
        Map<String, Object> current = mapper.lockAnyService(relayId);
        if (current == null || "DELETED".equals(current.get("status"))) throw missing();
        if ((number(current, "adminDisabled") != 0) != disabled)
        {
            if (mapper.setAdminDisabled(relayId, disabled) != 1) throw stale();
            mapper.insertAdminEvent(id(), number(current, "accountId"), UUID.randomUUID().toString().replace("-", ""),
                relayId, write(Map.of("relayId", Long.toString(relayId), "adminDisabled", disabled,
                    "administratorId", Long.toString(administratorId), "reason", reason.trim())));
        }
        return Map.of("relayId", Long.toString(relayId), "adminDisabled", disabled);
    }

    @Override public ResolvedRelay resolve(RuntimeBinding binding)
    {
        if (binding == null || binding.accountId() <= 0 || binding.applicationId() <= 0 || binding.sessionId() <= 0
            || binding.relayVersionId() <= 0 || !("LLM".equals(binding.capability()) || "ASR".equals(binding.capability()))
            || blank(binding.externalUserId()) || binding.externalUserId().length() > 128) throw bad("Relay 运行绑定无效");
        Map<String, Object> version = mapper.version(binding.accountId(), binding.relayVersionId());
        if (version == null) throw forbidden("Relay 版本不属于当前账号");
        Map<String, Object> current = require(binding.accountId(), number(version, "relayId"));
        String capability = binding.capability().toLowerCase();
        if (!"ACTIVE".equals(current.get("status")) || number(current, "adminDisabled") != 0
            || !Boolean.TRUE.equals(booleans(version.get("capabilities")).get(capability))
            || mapper.countRuntimeBinding(binding.accountId(), binding.applicationId(), binding.sessionId(),
                binding.relayVersionId(), binding.capability()) != 1
            || !granted(current, binding.applicationId(), binding.capability())) throw forbidden("Relay 当前不可用于此 Session");
        String token = crypto.decrypt(mapper.secret(binding.accountId(), number(current, "accessSecretId")));
        String baseUrl = (String) version.get("baseUrl");
        String pinnedAddress = targets.resolve(targets.validate(baseUrl)).getHostAddress();
        return new ResolvedRelay(baseUrl, pinnedAddress, "1", booleans(version.get("capabilities")),
            strings(version.get("endpoints")), ((Number) version.get("timeoutMs")).intValue(),
            number(version, "maxResponseBytes"), token, number(current, "authEpoch"),
            binding.externalUserId(), binding.applicationId(), binding.sessionId(), binding.turnId());
    }

    private boolean granted(Map<String, Object> current, long applicationId, String capability)
    {
        if ("ALL_ACCOUNT_APPS".equals(current.get("grantMode"))) return true;
        for (Map<String, Object> row : mapper.grants(number(current, "accountId"), number(current, "relayId")))
            if (number(row, "applicationId") == applicationId && "ACTIVE".equals(row.get("status"))
                && stringsList(row.get("scopes")).contains(capability)) return true;
        return false;
    }

    private void insertVersion(long accountId, long actorId, long relayId, int versionNo, ValidVersion version)
    {
        long versionId = id();
        mapper.insertVersion(versionId, accountId, relayId, versionNo, version.baseUrl(),
            write(version.capabilities()), write(version.endpoints()), version.timeoutMs(),
            version.maxResponseBytes(), digest(version.toString()), actorId);
        long epoch = number(require(accountId, relayId), "authEpoch");
        if (mapper.selectVersion(accountId, relayId, versionId, epoch) != 1) throw stale();
    }

    private ValidVersion validateVersion(VersionInput input)
    {
        if (input == null || !"1".equals(input.protocolVersion()) || input.capabilities() == null
            || !CAPABILITIES.containsAll(input.capabilities().keySet()) || input.capabilities().values().stream().anyMatch(Objects::isNull)
            || !Boolean.TRUE.equals(input.capabilities().get("llm")) && !Boolean.TRUE.equals(input.capabilities().get("asr"))
            || input.timeoutMs() == null || input.timeoutMs() < 1000 || input.timeoutMs() > 120000
            || input.maxResponseBytes() == null || input.maxResponseBytes() < 1 || input.maxResponseBytes() > 10485760)
            throw bad("Relay 版本能力或限值无效");
        Map<String, Boolean> capabilities = new TreeMap<>(input.capabilities());
        if ((Boolean.TRUE.equals(capabilities.get("image")) || Boolean.TRUE.equals(capabilities.get("tool")))
            && !Boolean.TRUE.equals(capabilities.get("llm"))) throw bad("图片和 Tool 能力必须附属于 LLM");
        Map<String, String> endpoints = new TreeMap<>();
        endpoints.put("capabilities", PATHS.get("capabilities"));
        for (String key : List.of("llm", "asr", "cancel"))
            if (Boolean.TRUE.equals(capabilities.get(key))) endpoints.put(key, PATHS.get(key));
        if (input.endpoints() != null && !endpoints.equals(input.endpoints())) throw bad("Relay 相对路径必须符合 LN_RELAY/1");
        URI uri = targets.validate(input.baseUrl());
        String baseUrl = uri.toString();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return new ValidVersion(baseUrl, capabilities, endpoints, input.timeoutMs(), input.maxResponseBytes());
    }

    private Map<String, Object> currentVersion(long accountId, Map<String, Object> service)
    {
        Map<String, Object> version = mapper.version(accountId, number(service, "currentVersionId"));
        if (version == null || number(version, "relayId") != number(service, "relayId")) throw conflict("Relay 当前版本损坏");
        return version;
    }

    private Map<String, Object> view(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("relayId", "name", "description", "status", "currentVersionId", "grantMode",
            "authEpoch", "adminDisabled", "lastTestAt", "lastTestStatus", "lastTestError", "lastTestVersionId",
            "tokenSuffix", "createdAt", "updatedAt"))
            if (row.containsKey(key)) out.put(key, row.get(key));
        for (String key : List.of("relayId", "currentVersionId", "authEpoch", "lastTestVersionId"))
            if (out.get(key) instanceof Number number) out.put(key, Long.toString(number.longValue()));
        return out;
    }

    private Map<String, Object> versionView(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("versionId", "relayId", "versionNo", "baseUrl", "protocolVersion",
            "timeoutMs", "maxResponseBytes", "createdAt")) if (row.containsKey(key)) out.put(key, row.get(key));
        out.put("versionId", Long.toString(number(row, "versionId")));
        out.put("relayId", Long.toString(number(row, "relayId")));
        out.put("capabilities", booleans(row.get("capabilities")));
        out.put("endpoints", strings(row.get("endpoints")));
        return out;
    }

    private Map<String, Object> grantView(Map<String, Object> row)
    { return Map.of("applicationId", Long.toString(number(row, "applicationId")),
        "scopes", stringsList(row.get("scopes")), "status", row.get("status")); }

    private Map<String, Object> require(long accountId, long relayId)
    {
        if (accountId <= 0 || relayId <= 0) throw missing();
        Map<String, Object> row = mapper.service(accountId, relayId);
        if (row == null) throw missing();
        return row;
    }
    private Map<String, Object> lock(long accountId, long relayId)
    {
        if (accountId <= 0 || relayId <= 0) throw missing();
        Map<String, Object> row = mapper.lockService(accountId, relayId);
        if (row == null) throw missing();
        return row;
    }
    private Long duplicate(long accountId, String operation, String key, byte[] hash)
    {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("Idempotency-Key 无效");
        if (accountId <= 0 || mapper.lockAccount(accountId) == null) throw forbidden("账号不可用");
        Map<String, Object> existing = mapper.idempotency(accountId, scope(operation), key);
        if (existing == null) return null;
        if (!Arrays.equals(hash, (byte[]) existing.get("requestHash"))) throw conflict("同一 Idempotency-Key 的请求参数不同");
        if (!"SUCCEEDED".equals(existing.get("status")) || number(existing, "resourceId") <= 0)
            throw conflict("Relay 操作仍在处理中");
        return number(existing, "resourceId");
    }
    private void remember(long accountId, String operation, String key, byte[] hash, long relayId)
    { mapper.insertIdempotency(id(), accountId, scope(operation), key, hash, relayId); }
    private Map<String, Object> replay(long accountId, long relayId)
    {
        Map<String, Object> row = mapper.serviceAnyStatus(accountId, relayId);
        if (row == null) throw conflict("幂等结果对应的 Relay 不存在");
        return "DELETED".equals(row.get("status")) ? view(row) : detail(accountId, relayId);
    }
    private static Map<String, Object> testView(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relayId", Long.toString(number(row, "relayId")));
        out.put("success", "SUCCESS".equals(row.get("lastTestStatus")));
        if (row.get("lastTestError") != null) out.put("errorCode", row.get("lastTestError"));
        return out;
    }
    private static String scope(String operation) { return HexFormat.of().formatHex(digest(operation)); }
    private long epoch(Map<String, Object> row, String ifMatch)
    {
        if (ifMatch == null || ifMatch.isBlank()) throw new ServiceException("缺少 If-Match", 428);
        long value = number(row, "authEpoch");
        if (!Long.toString(value).equals(ifMatch)) throw new ServiceException("Relay 版本已变化，请刷新后重试", 412);
        return value;
    }
    private static long number(Map<String, Object> row, String key)
    { Object value = row.get(key); return value instanceof Number number ? number.longValue() : 0; }
    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String suffix(String value) { return value.substring(Math.max(0, value.length() - 6)); }
    private static void requireToken(String value)
    {
        if (blank(value) || value.length() < 32 || value.length() > 4096 || !StandardCharsets.US_ASCII.newEncoder().canEncode(value)
            || value.chars().anyMatch(ch -> ch <= 32 || ch >= 127)) throw bad("Relay Token 格式无效");
    }
    private static boolean invalidScopes(List<String> scopes, Map<String, Boolean> capabilities)
    {
        if (scopes == null || scopes.isEmpty() || scopes.size() > 2 || scopes.size() != new HashSet<>(scopes).size()) return true;
        for (String scope : scopes)
            if (!("LLM".equals(scope) || "ASR".equals(scope)) || !Boolean.TRUE.equals(capabilities.get(scope.toLowerCase()))) return true;
        return false;
    }
    private Map<String, Boolean> booleans(Object raw)
    { try { return json.readValue(String.valueOf(raw), new TypeReference<Map<String, Boolean>>() { }); }
      catch (Exception error) { throw conflict("Relay 能力数据损坏"); } }
    private Map<String, String> strings(Object raw)
    { try { return json.readValue(String.valueOf(raw), new TypeReference<Map<String, String>>() { }); }
      catch (Exception error) { throw conflict("Relay 路径数据损坏"); } }
    private List<String> stringsList(Object raw)
    { try { return json.readValue(String.valueOf(raw), new TypeReference<List<String>>() { }); }
      catch (Exception error) { throw conflict("Relay 授权数据损坏"); } }
    private String write(Object value)
    { try { return json.writeValueAsString(value); } catch (Exception error) { throw bad("Relay 配置无法编码"); } }
    private static byte[] digest(String value)
    { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
      catch (Exception error) { throw new IllegalStateException(error); } }
    private long id() { long value = mapper.nextId(); if (value <= 0) throw conflict("Relay ID 生成失败"); return value; }
    private static ServiceException bad(String message) { return new ServiceException(message, 400); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, 403); }
    private static ServiceException conflict(String message) { return new ServiceException(message, 409); }
    private static ServiceException stale() { return conflict("Relay 状态已变化，请刷新后重试"); }
    private static ServiceException missing() { return new ServiceException("Relay 不存在", 404); }
    private record ValidVersion(String baseUrl, Map<String, Boolean> capabilities,
        Map<String, String> endpoints, int timeoutMs, long maxResponseBytes) { }
}
