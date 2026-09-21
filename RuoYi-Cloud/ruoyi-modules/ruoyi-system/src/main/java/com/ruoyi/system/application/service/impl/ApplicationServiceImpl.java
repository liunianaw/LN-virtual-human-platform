package com.ruoyi.system.application.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.application.dto.ApplicationConfigRequest;
import com.ruoyi.system.application.dto.ApplicationStatusRequest;
import com.ruoyi.system.application.dto.CreateApplicationRequest;
import com.ruoyi.system.application.mapper.ApplicationMapper;
import com.ruoyi.system.application.service.IApplicationService;

/** Owns the immutable USER application configuration and its APP_CURRENT references. */
@Service
public class ApplicationServiceImpl implements IApplicationService
{
    private static final Map<String, Integer> LIMITS = Map.of("maxTextCodePoints", 8000, "maxCodePointsPerSegment", 200,
        "maxConcurrentSegments", 2, "maxBufferedSegments", 2, "maxAudioBytes", 5242880, "ttsTimeoutSeconds", 30,
        "turnTimeoutSeconds", 300, "playbackWaitSeconds", 60, "temporaryAudioTtlSeconds", 900);
    private final ApplicationMapper mapper;
    private final ObjectMapper json;

    public ApplicationServiceImpl(ApplicationMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    @Override
    public Map<String, Object> list(long accountId, Integer requestedPage, Integer requestedSize, String requestedStatus)
    {
        int page = requestedPage == null ? 1 : requestedPage, size = requestedSize == null ? 20 : requestedSize;
        if (accountId <= 0 || page < 1 || size < 1 || size > 100) throw badRequest("分页参数无效");
        String status = blank(requestedStatus) ? null : requestedStatus.trim();
        if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status)) throw badRequest("应用状态筛选无效");
        return Map.of("items", mapper.selectApplications(accountId, status, (page - 1) * size, size),
            "total", mapper.countApplications(accountId, status), "pageNum", page, "pageSize", size);
    }

    @Override
    public Map<String, Object> detail(long accountId, long applicationId)
    {
        Map<String, Object> application = required(mapper.selectApplication(accountId, applicationId));
        Map<String, Object> result = new LinkedHashMap<>(application);
        result.put("versions", mapper.selectConfigVersions(accountId, applicationId));
        if (application.get("currentConfigVersionId") != null)
            result.put("currentConfig", mapper.selectConfig(accountId, applicationId, asLong(application.get("currentConfigVersionId"))));
        return result;
    }

    @Override
    public Map<String, Object> config(long accountId, long applicationId, long configVersionId)
    { return required(mapper.selectConfig(accountId, applicationId, configVersionId)); }

    @Override
    public Map<String, Object> choices(long accountId)
    {
        requireAccount(accountId);
        return Map.of("avatars", mapper.selectAvatarChoices(accountId), "voices", mapper.selectVoiceChoices());
    }

    @Override
    @Transactional
    public Map<String, Object> create(long accountId, CreateApplicationRequest request, String key)
    {
        requireAccount(accountId); requireKey(key);
        if (request == null || blank(request.name())) throw badRequest("应用名称不能为空");
        String name = request.name().trim(), description = blank(request.description()) ? null : request.description().trim();
        byte[] hash = digest("create|" + name + "|" + (description == null ? "" : description));
        long existing = existing(accountId, scope("application:create"), key, hash);
        if (existing > 0) return createResponse(required(mapper.selectApplication(accountId, existing)));
        long id = mapper.nextId(); Instant now = Instant.now();
        mapper.insertApplication(id, accountId, name, description, now);
        mapper.insertIdempotency(mapper.nextId(), accountId, scope("application:create"), key, hash, "APPLICATION", id, now.plus(24, ChronoUnit.HOURS));
        return createResponse(required(mapper.selectApplication(accountId, id)));
    }

    @Override
    @Transactional
    public Map<String, Object> publish(long accountId, long applicationId, ApplicationConfigRequest request, String ifMatch, String key)
    {
        requireAccount(accountId); requireKey(key); Config input = config(request);
        byte[] hash = digest("publish|" + applicationId + "|" + input.normalized());
        long existing = existing(accountId, scope("application:publish:" + applicationId), key, hash);
        if (existing > 0) return publishResponse(accountId, applicationId, existing);
        Map<String, Object> app = required(mapper.selectApplicationForUpdate(accountId, applicationId));
        if (!"ACTIVE".equals(app.get("status"))) throw conflict("停用应用不能发布配置");
        requireRevision(ifMatch, asLong(app.get("revision")));
        // App first, then dependencies in fixed Avatar→Voice order; both checks lock their authoritative version rows.
        if (mapper.countAvailableAvatarVersion(accountId, input.avatarVersionId()) != 1) throw unavailable("Avatar 版本不可用于新绑定");
        if (mapper.countAvailableOfficialVoiceVersion(input.voiceVersionId()) != 1) throw unavailable("仅可绑定已发布的官方声音版本");
        int versionNo = mapper.nextConfigVersionNo(applicationId);
        long configId = mapper.nextId(); Instant now = Instant.now();
        mapper.insertConfig(configId, accountId, applicationId, versionNo, input.avatarVersionId(), input.voiceVersionId(),
            text(Map.of("enabled", false)), text(LIMITS), digest(input.normalized()), now);
        if (mapper.replaceCurrentConfig(applicationId, configId, asLong(app.get("revision"))) != 1) throw conflict("应用配置已变化，请刷新后重试");
        mapper.releaseCurrentReferences(accountId, applicationId);
        String operation = "application:" + applicationId + ":" + key;
        reference(accountId, applicationId, operation, "APP_CONFIG", configId, now);
        reference(accountId, applicationId, operation, "AVATAR_VERSION", input.avatarVersionId(), now);
        reference(accountId, applicationId, operation, "VOICE_VERSION", input.voiceVersionId(), now);
        mapper.insertIdempotency(mapper.nextId(), accountId, scope("application:publish:" + applicationId), key, hash,
            "APP_CONFIG", configId, now.plus(24, ChronoUnit.HOURS));
        return publishResponse(accountId, applicationId, configId);
    }

    @Override
    @Transactional
    public Map<String, Object> changeStatus(long accountId, long applicationId, ApplicationStatusRequest request, String ifMatch, String key)
    {
        requireAccount(accountId); requireKey(key);
        if (request == null || !Set.of("ACTIVE", "DISABLED").contains(request.status()) || blank(request.reason())) throw badRequest("应用状态参数无效");
        String status = request.status(), reason = request.reason().trim();
        byte[] hash = digest("status|" + applicationId + "|" + status + "|" + reason);
        long existing = existing(accountId, scope("application:status:" + applicationId), key, hash);
        if (existing > 0) return statusResponse(required(mapper.selectApplication(accountId, applicationId)));
        Map<String, Object> app = required(mapper.selectApplicationForUpdate(accountId, applicationId));
        requireRevision(ifMatch, asLong(app.get("revision")));
        if (status.equals(app.get("status")))
        {
            Instant now = Instant.now();
            mapper.insertIdempotency(mapper.nextId(), accountId, scope("application:status:" + applicationId), key, hash,
                "APPLICATION", applicationId, now.plus(24, ChronoUnit.HOURS));
            return statusResponse(required(mapper.selectApplication(accountId, applicationId)));
        }
        if ("ACTIVE".equals(status) && mapper.countCurrentConfigAvailable(applicationId) != 1)
            throw conflict("重新启用前需先发布仍然可用的配置");
        if (mapper.updateStatus(applicationId, status, asLong(app.get("revision"))) != 1) throw conflict("应用状态已变化，请刷新后重试");
        Instant now = Instant.now(); String eventId = UUID.randomUUID().toString().replace("-", "");
        mapper.insertOutbox(mapper.nextId(), accountId, eventId, Long.toString(applicationId),
            text(Map.of("applicationId", Long.toString(applicationId), "status", status, "reason", reason)), now);
        mapper.insertIdempotency(mapper.nextId(), accountId, scope("application:status:" + applicationId), key, hash,
            "APPLICATION", applicationId, now.plus(24, ChronoUnit.HOURS));
        return statusResponse(required(mapper.selectApplication(accountId, applicationId)));
    }

    private Config config(ApplicationConfigRequest request)
    {
        if (request == null || !"SPEAK_ONLY".equals(request.mode()) || request.contextPolicy() == null || !Boolean.FALSE.equals(request.contextPolicy().enabled())
            || request.runtimeLimits() != null && !request.runtimeLimits().isEmpty() || request.skills() != null && !request.skills().isEmpty()
            || !blank(request.llmRelayVersionId()) || !blank(request.asrRelayVersionId())) throw badRequest("本轮仅允许关闭上下文的 SPEAK_ONLY 官方声音配置");
        return new Config(id(request.avatarVersionId()), id(request.voiceVersionId()));
    }

    private long existing(long accountId, String scope, String key, byte[] expectedHash)
    {
        Map<String, Object> item = mapper.selectIdempotencyForUpdate(accountId, scope, key);
        if (item == null) return 0;
        if (!Arrays.equals((byte[]) item.get("requestHash"), expectedHash)) throw conflict("同一 Idempotency-Key 的参数不同");
        return asLong(item.get("resourceId"));
    }
    private void reference(long accountId, long appId, String operation, String type, long resourceId, Instant now)
    { mapper.insertCurrentReference(mapper.nextId(), accountId, appId, operation, type, resourceId, now); }
    private Map<String, Object> createResponse(Map<String, Object> app)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", app.get("applicationId")); result.put("status", app.get("status"));
        result.put("configStatus", app.get("configStatus")); result.put("currentConfigVersionId", null); result.put("ETag", app.get("revision"));
        return result;
    }
    private Map<String, Object> publishResponse(long accountId, long applicationId, long configId)
    {
        Map<String, Object> config = required(mapper.selectConfig(accountId, applicationId, configId));
        return Map.of("applicationId", Long.toString(applicationId), "configVersionId", config.get("configVersionId"), "versionNo", config.get("versionNo"),
            "configHash", config.get("configHash"), "capabilities", List.of("avatar:read", "speak:write"), "effectiveLimits", LIMITS);
    }
    private Map<String, Object> statusResponse(Map<String, Object> app)
    { return Map.of("applicationId", app.get("applicationId"), "status", app.get("status"), "revision", app.get("revision")); }
    private static Map<String, Object> required(Map<String, Object> value) { if (value == null) throw forbidden("应用或配置不存在，或无权访问"); return value; }
    private static void requireAccount(long id) { if (id <= 0) throw forbidden("当前后台登录无效"); }
    private static long id(String value) { try { long id = Long.parseLong(value); if (id > 0) return id; } catch (RuntimeException ignored) { } throw badRequest("资源版本标识无效"); }
    private static long asLong(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()); }
    private static void requireKey(String key) { if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw badRequest("Idempotency-Key 无效"); }
    private static void requireRevision(String value, long revision) { if (blank(value)) throw new ServiceException("缺少 If-Match", HttpStatus.PRECONDITION_REQUIRED.value()); if (!Long.toString(revision).equals(value.replace("\"", "").trim())) throw new ServiceException("应用已变化，请刷新后重试", HttpStatus.PRECONDITION_FAILED.value()); }
    private String text(Map<String, ?> value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException error) { throw new IllegalStateException("配置序列化失败", error); } }
    private static String scope(String value) { return hex(digest(value)); }
    private static byte[] digest(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception error) { throw new IllegalStateException("SHA-256 不可用", error); } }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST.value()); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN.value()); }
    private static ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT.value()); }
    private static ServiceException unavailable(String message) { return new ServiceException(message, HttpStatus.UNPROCESSABLE_ENTITY.value()); }
    private record Config(long avatarVersionId, long voiceVersionId) { String normalized() { return "SPEAK_ONLY|" + avatarVersionId + "|" + voiceVersionId + "|{enabled:false}|maxTextCodePoints=8000|maxCodePointsPerSegment=200|maxConcurrentSegments=2|maxBufferedSegments=2|maxAudioBytes=5242880|ttsTimeoutSeconds=30|turnTimeoutSeconds=300|playbackWaitSeconds=60|temporaryAudioTtlSeconds=900"; } }
}
