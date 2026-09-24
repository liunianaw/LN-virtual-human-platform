package com.ruoyi.system.developer.access.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.developer.access.mapper.AccessKeyMapper;
import com.ruoyi.system.developer.access.service.IAccessKeyService;

@Service
public class AccessKeyServiceImpl implements IAccessKeyService
{
    private static final Set<String> MANAGEMENT_SCOPES = Set.of("assets:read", "assets:write", "generation:read",
        "generation:write", "config:read", "config:write", "keys:write", "usage:read", "webhooks:write");
    private static final List<String> SESSION_SCOPES = List.of("sessions:create", "sessions:read", "sessions:grant", "sessions:end", "sessions:revoke");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccessKeyMapper mapper;
    private final ObjectMapper json;
    private final byte[] pepper;

    public AccessKeyServiceImpl(AccessKeyMapper mapper, ObjectMapper json,
        @Value("${ln.access-key.pepper:${LN_ACCESS_KEY_PEPPER:}}") String configuredPepper)
    {
        this.mapper = mapper;
        this.json = json;
        this.pepper = configuredPepper.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Principal authenticate(String authorization, String requiredType, String requiredScope)
    {
        if (authorization == null || !authorization.matches("Bearer ln[ma]_[0-9a-f]{32}_[A-Za-z0-9_-]{43}")) throw unauthorized();
        String raw = authorization.substring(7);
        if (!("MANAGEMENT".equals(requiredType) ? raw.startsWith("lnm_") : raw.startsWith("lna_"))) throw unauthorized();
        Map<String, Object> row = mapper.byPublicId(raw.substring(4, 36));
        if (row == null || !"hmac-sha256-v1".equals(row.get("hashKeyVersion"))
            || !MessageDigest.isEqual((byte[]) row.get("secretHash"), digest(raw))
            || !"ACTIVE".equals(row.get("status")) || !"0".equals(String.valueOf(row.get("accountStatus")))
            || !"0".equals(String.valueOf(row.get("accountDeleted")))
            || row.get("expiresAt") != null && ((java.util.Date) row.get("expiresAt")).toInstant().isBefore(Instant.now()))
            throw unauthorized();
        if (!requiredType.equals(row.get("keyType"))) throw unauthorized();
        Long applicationId = row.get("applicationId") == null ? null : number(row.get("applicationId"));
        if (applicationId != null && (!"ACTIVE".equals(row.get("applicationStatus")) || number(row.get("adminDisabled")) != 0))
            throw forbidden("应用当前不可用");
        List<String> scopes = readScopes(row.get("scopes"));
        if (requiredScope != null && !scopes.contains(requiredScope)) throw forbidden("凭证权限不足");
        long id = number(row.get("id"));
        mapper.touch(id);
        return new Principal(id, number(row.get("accountId")), applicationId, requiredType, scopes, number(row.get("authEpoch")));
    }

    @Override
    public List<Map<String, Object>> list(long accountId, String type, Long applicationId)
    {
        account(accountId, false);
        if (!Set.of("MANAGEMENT", "APPLICATION").contains(type) || "APPLICATION".equals(type) && applicationId == null)
            throw bad("凭证类型无效");
        if (applicationId != null) application(accountId, applicationId, false);
        return mapper.list(accountId, type, applicationId);
    }

    @Override
    @Transactional
    public Map<String, Object> createManagement(long accountId, String name, List<String> scopes, String key, Principal caller)
    {
        account(accountId, true);
        String safeName = name(name);
        List<String> safeScopes = scopes(scopes, caller);
        byte[] requestHash = sha("create|" + safeName + "|" + String.join(",", safeScopes));
        Long previous = previous(accountId, "key:create", key, requestHash);
        if (previous != null) return summary(accountId, previous);
        return issue(accountId, null, "MANAGEMENT", safeName, safeScopes, "key:create", key, requestHash, caller);
    }

    @Override
    @Transactional
    public Map<String, Object> rotateManagement(long accountId, long keyId, String key, Principal caller)
    {
        account(accountId, true);
        String scope = "key:rotate:" + keyId;
        byte[] requestHash = sha(scope);
        Long previous = previous(accountId, scope, key, requestHash);
        if (previous != null) return summary(accountId, previous);
        Map<String, Object> old = ownedKey(accountId, keyId, "MANAGEMENT");
        if (!"ACTIVE".equals(old.get("status"))) throw conflict("凭证已停用");
        List<String> safeScopes = readScopes(old.get("scopes"));
        if (caller != null && !caller.scopes().containsAll(safeScopes)) throw forbidden("不能提升管理 Key 权限");
        mapper.changeStatus(keyId, "DISABLED");
        event(accountId, keyId, "DISABLED", caller);
        return issue(accountId, null, "MANAGEMENT", old.get("name").toString(), safeScopes, scope, key, requestHash, caller);
    }

    @Override
    @Transactional
    public Map<String, Object> resetApplication(long accountId, long applicationId, String name, String key, Principal caller)
    {
        account(accountId, true);
        Map<String, Object> app = application(accountId, applicationId, true);
        if (!"ACTIVE".equals(app.get("status")) || number(app.get("adminDisabled")) != 0)
            throw forbidden("应用当前不可重置 Secret");
        String scope = "key:application:reset:" + applicationId;
        String safeName = name(name);
        byte[] requestHash = sha(scope + "|" + safeName);
        Long previous = previous(accountId, scope, key, requestHash);
        if (previous != null) return summary(accountId, previous);
        Map<String, Object> old = mapper.activeApplication(applicationId);
        if (old != null)
        {
            long oldId = number(old.get("id"));
            mapper.changeStatus(oldId, "DISABLED");
            event(accountId, oldId, "DISABLED", caller);
        }
        // The application row lock serializes resets; the generated unique key is the database backstop.
        return issue(accountId, applicationId, "APPLICATION", safeName, SESSION_SCOPES, scope, key, requestHash, caller);
    }

    @Override
    @Transactional
    public Map<String, Object> changeApplicationStatus(long accountId, long applicationId, long keyId, String status, String key, Principal caller)
    {
        account(accountId, true);
        application(accountId, applicationId, true);
        if (!Set.of("DISABLED", "DELETED").contains(status)) throw bad("凭证状态无效");
        String scope = "key:application:status:" + applicationId + ":" + keyId;
        byte[] requestHash = sha(scope + "|" + status);
        Long previous = previous(accountId, scope, key, requestHash);
        if (previous != null) return summary(accountId, previous);
        Map<String, Object> old = ownedKey(accountId, keyId, "APPLICATION");
        if (number(old.get("applicationId")) != applicationId) throw forbidden("凭证不属于该应用");
        if (!"ACTIVE".equals(old.get("status")) && !("DELETED".equals(status) && "DISABLED".equals(old.get("status"))))
            throw conflict("凭证状态已变化");
        mapper.changeStatus(keyId, status);
        event(accountId, keyId, status, caller);
        remember(accountId, scope, key, requestHash, keyId);
        return summary(accountId, keyId);
    }

    @Override
    @Transactional
    public Map<String, Object> changeStatus(long accountId, long keyId, String status, String key, Principal caller)
    {
        account(accountId, true);
        if (!Set.of("DISABLED", "DELETED").contains(status)) throw bad("凭证状态无效");
        String scope = "key:status:" + keyId;
        byte[] requestHash = sha(scope + "|" + status);
        Long previous = previous(accountId, scope, key, requestHash);
        if (previous != null) return summary(accountId, previous);
        Map<String, Object> old = ownedKey(accountId, keyId, "MANAGEMENT");
        if (caller != null && caller.keyId() == keyId) throw forbidden("不能用当前 Key 停用自身");
        if (!"ACTIVE".equals(old.get("status")) && !("DELETED".equals(status) && "DISABLED".equals(old.get("status"))))
            throw conflict("凭证状态已变化");
        mapper.changeStatus(keyId, status);
        event(accountId, keyId, status, caller);
        remember(accountId, scope, key, requestHash, keyId);
        return summary(accountId, keyId);
    }

    private Map<String, Object> issue(long accountId, Long applicationId, String type, String name, List<String> scopes,
        String scope, String requestId, byte[] requestHash, Principal caller)
    {
        byte[] identity = new byte[16], secretBytes = new byte[32];
        RANDOM.nextBytes(identity); RANDOM.nextBytes(secretBytes);
        String publicId = HexFormat.of().formatHex(identity);
        String secret = ("MANAGEMENT".equals(type) ? "lnm_" : "lna_") + publicId + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        long id = mapper.nextId();
        try
        {
            mapper.insert(id, accountId, applicationId, type, name, publicId, digest(secret),
                secret.substring(secret.length() - 8), json.writeValueAsString(scopes), Instant.now());
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("凭证权限序列化失败", error); }
        remember(accountId, scope, requestId, requestHash, id);
        event(accountId, id, "ACTIVE", caller);
        Map<String, Object> result = new LinkedHashMap<>(summary(accountId, id));
        result.put("secret", secret);
        return result;
    }

    private void event(long accountId, long id, String status, Principal caller)
    {
        String eventId = UUID.randomUUID().toString();
        mapper.outbox(mapper.nextId(), accountId, eventId, Long.toString(id),
            "{\"keyId\":\"" + id + "\",\"status\":\"" + status + "\"}", Instant.now());
        mapper.audit(mapper.nextId(), accountId, id, caller == null ? "CONSOLE" : "MANAGEMENT",
            caller == null ? null : caller.keyId(), status);
    }
    private Long previous(long accountId, String operation, String key, byte[] hash)
    {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("Idempotency-Key 无效");
        Map<String, Object> row = mapper.idempotency(accountId, HexFormat.of().formatHex(sha(operation)), key);
        if (row == null) return null;
        if (!MessageDigest.isEqual((byte[]) row.get("requestHash"), hash)) throw conflict("同一 Idempotency-Key 的参数不同");
        return number(row.get("resourceId"));
    }
    private void remember(long accountId, String operation, String key, byte[] hash, long id)
    { mapper.insertIdempotency(mapper.nextId(), accountId, HexFormat.of().formatHex(sha(operation)), key, hash, id, Instant.now().plus(24, ChronoUnit.HOURS)); }
    private Map<String, Object> summary(long accountId, long keyId)
    { Map<String, Object> result = mapper.summary(accountId, keyId); if (result == null) throw forbidden("凭证不存在"); return result; }
    private Map<String, Object> ownedKey(long accountId, long keyId, String type)
    { Map<String, Object> row = mapper.byIdForUpdate(accountId, keyId); if (row == null || !type.equals(row.get("keyType"))) throw forbidden("凭证不存在"); return row; }
    private void account(long accountId, boolean lock)
    {
        Map<String, Object> row = lock ? mapper.accountForUpdate(accountId) : mapper.account(accountId);
        if (row == null || !"0".equals(String.valueOf(row.get("status"))) || !"0".equals(String.valueOf(row.get("delFlag")))) throw forbidden("账号不可用");
    }
    private Map<String, Object> application(long accountId, long applicationId, boolean lock)
    { Map<String, Object> row = lock ? mapper.applicationForUpdate(accountId, applicationId) : mapper.application(accountId, applicationId); if (row == null) throw forbidden("应用不存在或无权访问"); return row; }
    private static String name(String value)
    { if (value == null || value.isBlank() || value.trim().length() > 100) throw bad("凭证名称无效"); return value.trim(); }
    private static List<String> scopes(List<String> requested, Principal caller)
    {
        if (requested == null || requested.isEmpty() || !MANAGEMENT_SCOPES.containsAll(requested)) throw bad("管理 Key 权限无效");
        List<String> sorted = new ArrayList<>(Set.copyOf(requested)); sorted.sort(String::compareTo);
        if (caller != null && !caller.scopes().containsAll(sorted)) throw forbidden("不能提升管理 Key 权限");
        return sorted;
    }
    private List<String> readScopes(Object value)
    { try { return json.readValue(value.toString(), new TypeReference<List<String>>() {}); } catch (Exception error) { throw forbidden("凭证权限无效"); } }
    private byte[] digest(String raw)
    {
        if (pepper.length < 32) throw new IllegalStateException("LN_ACCESS_KEY_PEPPER 至少需要 32 字节随机值");
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(pepper, "HmacSHA256")); return mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException("凭证摘要不可用", error); }
    }
    private static byte[] sha(String value)
    { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static long number(Object value)
    { return value instanceof Boolean flag ? (flag ? 1 : 0) : value instanceof Number number ? number.longValue() : Long.parseLong(value.toString()); }
    private static ServiceException unauthorized() { return new ServiceException("接入凭证无效", 401); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, 403); }
    private static ServiceException bad(String message) { return new ServiceException(message, 400); }
    private static ServiceException conflict(String message) { return new ServiceException(message, 409); }
}
