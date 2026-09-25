package com.ruoyi.system.application.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
            result.put("currentConfig", config(accountId, applicationId, asLong(application.get("currentConfigVersionId"))));
        return result;
    }

    @Override
    public Map<String, Object> config(long accountId, long applicationId, long configVersionId)
    {
        Map<String, Object> result = new LinkedHashMap<>(required(mapper.selectConfig(accountId, applicationId, configVersionId)));
        result.put("skills", mapper.selectConfigSkills(configVersionId));
        return result;
    }

    @Override
    public Map<String, Object> choices(long accountId)
    {
        requireAccount(accountId);
        return Map.of("avatars", mapper.selectAvatarChoices(accountId), "voices", mapper.selectVoiceChoices(),
            "relays", mapper.selectRelayChoices(accountId), "skills", mapper.selectSkillChoices(accountId));
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
        if (!"ACTIVE".equals(app.get("status")) || asLong(app.get("adminDisabled")) != 0) throw conflict("应用当前不能发布配置");
        requireRevision(ifMatch, asLong(app.get("revision")));
        // Lock order is Application → Avatar → Voice → Relay → Skill.
        if (mapper.countAvailableAvatarVersion(accountId, input.avatarVersionId()) != 1) throw unavailable("Avatar 版本不可用于新绑定");
        if (mapper.countAvailableOfficialVoiceVersion(input.voiceVersionId()) != 1) throw unavailable("仅可绑定已发布的官方声音版本");
        if (input.llmRelayVersionId() != null)
            requireRelay(accountId, applicationId, input.llmRelayVersionId(), "llm", input);
        if (input.asrRelayVersionId() != null)
            requireRelay(accountId, applicationId, input.asrRelayVersionId(), "asr", input);
        Set<String> toolNames = new HashSet<>();
        for (ApplicationConfigRequest.SkillBinding skill : input.skills())
        {
            Map<String, Object> binding = mapper.selectSkillBindingForUpdate(accountId, id(skill.skillVersionId()));
            if (binding == null) throw unavailable("Skill 版本不可用于新绑定");
            if (skill.enabled() && "HTTP_TOOL".equals(binding.get("skillType")))
            {
                String toolName = (String) binding.get("toolName");
                if (toolName == null || Set.of("ln_capture_context", "ln_highlight_element").contains(toolName))
                    throw unavailable("Skill Tool 名称与平台保留能力冲突");
                if (!toolNames.add(toolName)) throw conflict("同一应用的 Tool 名称重复");
                if (!Boolean.TRUE.equals(input.llmCapabilities().get("tool"))) throw unavailable("启用 Tool 需要模型与 Relay 声明 Tool 能力");
            }
            if (skill.enabled()) checkContextRequirement(binding.get("contextRequirements"), input.contextPolicy());
        }
        int versionNo = mapper.nextConfigVersionNo(applicationId);
        long configId = mapper.nextId(); Instant now = Instant.now();
        mapper.insertConfig(configId, accountId, applicationId, versionNo, input.mode(),
            input.avatarVersionId(), input.voiceVersionId(), input.llmRelayVersionId(), input.asrRelayVersionId(),
            input.llmModelId(), input.systemPrompt(), nullableText(input.llmParameters()),
            nullableText(input.llmCapabilities()), text(input.contextPolicy()), text(input.runtimeLimits()),
            digest(input.normalized()), now);
        for (ApplicationConfigRequest.SkillBinding skill : input.skills())
            mapper.insertConfigSkill(mapper.nextId(), accountId, configId, id(skill.skillVersionId()),
                skill.enabled(), skill.sortOrder(), now);
        if (mapper.replaceCurrentConfig(applicationId, configId, text(input.contextPolicy()), asLong(app.get("revision"))) != 1)
            throw conflict("应用配置已变化，请刷新后重试");
        mapper.releaseCurrentReferences(accountId, applicationId);
        String operation = "application:" + applicationId + ":" + key;
        reference(accountId, applicationId, operation, "APP_CONFIG", configId, now);
        reference(accountId, applicationId, operation, "AVATAR_VERSION", input.avatarVersionId(), now);
        reference(accountId, applicationId, operation, "VOICE_VERSION", input.voiceVersionId(), now);
        if (input.llmRelayVersionId() != null)
            reference(accountId, applicationId, operation, "RELAY_VERSION", input.llmRelayVersionId(), now);
        if (input.asrRelayVersionId() != null && !input.asrRelayVersionId().equals(input.llmRelayVersionId()))
            reference(accountId, applicationId, operation, "RELAY_VERSION", input.asrRelayVersionId(), now);
        for (ApplicationConfigRequest.SkillBinding skill : input.skills())
            reference(accountId, applicationId, operation, "SKILL_VERSION", id(skill.skillVersionId()), now);
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
        if ("ACTIVE".equals(status) && asLong(app.get("adminDisabled")) != 0) throw forbidden("管理员已禁用应用");
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

    @Override
    @Transactional
    public Map<String, Object> changeAdminDisabled(long administratorId, long applicationId, boolean disabled, String reason)
    {
        if (administratorId <= 0 || blank(reason) || reason.trim().length() > 500) throw badRequest("管理员操作原因无效");
        Map<String, Object> app = required(mapper.selectAdminApplicationForUpdate(applicationId));
        if ((asLong(app.get("adminDisabled")) != 0) != disabled)
        {
            mapper.updateAdminDisabled(applicationId, disabled);
            String eventId = UUID.randomUUID().toString().replace("-", "");
            Instant now = Instant.now();
            mapper.insertAdminOutbox(mapper.nextId(), asLong(app.get("accountId")), eventId, Long.toString(applicationId),
                text(Map.of("applicationId", Long.toString(applicationId), "adminDisabled", disabled,
                    "administratorId", Long.toString(administratorId), "reason", reason.trim())), now);
        }
        return Map.of("applicationId", Long.toString(applicationId), "adminDisabled", disabled);
    }

    private Config config(ApplicationConfigRequest request)
    {
        if (request == null || !Set.of("CHAT", "SPEAK_ONLY").contains(request.mode()))
            throw badRequest("应用模式无效");
        long avatar = id(request.avatarVersionId()), voice = id(request.voiceVersionId());
        Map<String, Object> context = request.contextPolicy();
        if (context == null || !(context.get("enabled") instanceof Boolean)
            || !Set.of("enabled", "modes", "sources", "captureScope", "targets", "dom", "fullPageEnabled",
                "resultMode", "highlightMode", "maxCapturesPerTurn").containsAll(context.keySet()))
            throw badRequest("Context 策略无效");
        boolean contextEnabled = Boolean.TRUE.equals(context.get("enabled"));
        if (contextEnabled) validateContext(context);
        else if (!Map.of("enabled", false).equals(context)) throw badRequest("关闭 Context 时不能附带采集权限");
        Map<String, Object> limits = new TreeMap<>(LIMITS);
        if (request.runtimeLimits() != null)
        {
            if (!Set.of("toolCallsPerTurn", "capturesPerTurn").containsAll(request.runtimeLimits().keySet()))
                throw badRequest("运行限值字段无效");
            for (Map.Entry<String, Object> entry : request.runtimeLimits().entrySet())
            {
                if (!(entry.getValue() instanceof Integer value) || value < 0 || value > 20)
                    throw badRequest("运行限值超出范围");
                limits.put(entry.getKey(), value);
            }
        }
        List<ApplicationConfigRequest.SkillBinding> skills = request.skills() == null ? List.of() : request.skills();
        if (skills.size() > 20) throw badRequest("Skill 数量超出范围");
        Set<Long> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (ApplicationConfigRequest.SkillBinding skill : skills)
            if (skill == null || !ids.add(id(skill.skillVersionId())) || skill.sortOrder() < 0
                || !orders.add(skill.sortOrder())) throw badRequest("Skill 绑定重复或顺序无效");
        List<ApplicationConfigRequest.SkillBinding> ordered = new ArrayList<>(skills);
        ordered.sort(java.util.Comparator.comparingInt(ApplicationConfigRequest.SkillBinding::sortOrder));
        Long llm = optionalId(request.llmRelayVersionId()), asr = optionalId(request.asrRelayVersionId());
        Map<String, Boolean> capabilities = request.llmCapabilities() == null ? Map.of() : new TreeMap<>(request.llmCapabilities());
        if (!Set.of("image", "tool").containsAll(capabilities.keySet()) || capabilities.containsValue(null))
            throw badRequest("模型能力声明无效");
        Map<String, Object> parameters = request.llmParameters() == null ? Map.of() : new TreeMap<>(request.llmParameters());
        if (!Set.of("temperature", "maxOutputTokens").containsAll(parameters.keySet())) throw badRequest("模型参数无效");
        Object temperature = parameters.get("temperature"), maxOutput = parameters.get("maxOutputTokens");
        if (temperature != null && (!(temperature instanceof Number n) || !Double.isFinite(n.doubleValue())
            || n.doubleValue() < 0 || n.doubleValue() > 2)
            || maxOutput != null && (!(maxOutput instanceof Integer n) || n < 1 || n > 8192))
            throw badRequest("模型参数超出范围");
        String model = blank(request.llmModelId()) ? null : request.llmModelId().trim();
        String prompt = blank(request.systemPrompt()) ? null : request.systemPrompt().trim();
        if ("CHAT".equals(request.mode()))
        {
            if (llm == null || model == null || model.length() > 128 || prompt != null && prompt.length() > 32768)
                throw badRequest("CHAT 必须指定 LLM Relay 和有效模型 ID");
            if (contextEnabled && !Boolean.TRUE.equals(capabilities.get("image")))
                throw badRequest("Context 需要模型图片能力");
            if (contextEnabled && modes(context).contains("AI_ON_DEMAND") && !Boolean.TRUE.equals(capabilities.get("tool")))
                throw badRequest("AI 按需采集需要模型 Tool 能力");
        }
        else if (llm != null || asr != null || model != null || prompt != null || !parameters.isEmpty()
            || !capabilities.isEmpty() || !skills.isEmpty() || contextEnabled)
            throw badRequest("SPEAK_ONLY 仅允许 Avatar 与官方 Voice");
        Map<String, Object> normalized = new TreeMap<>();
        normalized.put("mode", request.mode());
        normalized.put("avatarVersionId", avatar);
        normalized.put("voiceVersionId", voice);
        normalized.put("llmRelayVersionId", llm);
        normalized.put("asrRelayVersionId", asr);
        normalized.put("llmModelId", model);
        normalized.put("systemPrompt", prompt);
        normalized.put("llmParameters", parameters);
        normalized.put("llmCapabilities", capabilities);
        normalized.put("contextPolicy", context);
        normalized.put("runtimeLimits", limits);
        normalized.put("skills", ordered);
        return new Config(request.mode(), avatar, voice, llm, asr, model, prompt, parameters, capabilities,
            context, limits, ordered, text(canonical(normalized)));
    }

    private void requireRelay(long accountId, long applicationId, long versionId, String capability, Config config)
    {
        Map<String, Object> relay = mapper.selectRelayBindingForUpdate(accountId, applicationId, versionId, capability);
        if (relay == null) throw unavailable("Relay 版本、能力或应用授权不可用");
        JsonNode claims = jsonNode(relay.get("capabilities"));
        if ("llm".equals(capability) && (Boolean.TRUE.equals(config.llmCapabilities().get("image"))
            && !claims.path("image").asBoolean() || Boolean.TRUE.equals(config.llmCapabilities().get("tool"))
            && !claims.path("tool").asBoolean()))
            throw unavailable("Relay 未声明模型所需的图片或 Tool 能力");
    }

    private void checkContextRequirement(Object requirement, Map<String, Object> context)
    {
        JsonNode needs = jsonNode(requirement);
        for (String source : List.of("element", "page", "hybrid"))
            if (needs.path(source).asBoolean() && (!Boolean.TRUE.equals(context.get("enabled"))
                || !(sources(context).contains(source.toUpperCase())
                    || !"hybrid".equals(source) && sources(context).contains("HYBRID"))))
                throw unavailable("Skill 所需 Context 来源未授权");
    }

    private void validateContext(Map<String, Object> context)
    {
        Set<String> modes = modes(context), sources = sources(context);
        if (text(context).length() > 32768 || modes.isEmpty() || sources.isEmpty()
            || !Set.of("EXPLICIT", "AI_ON_DEMAND").containsAll(modes)
            || !Set.of("ELEMENT", "PAGE", "HYBRID").containsAll(sources)
            || !("PARTIAL".equals(context.get("resultMode")) || "STRICT".equals(context.get("resultMode")))
            || !("EVENT_ONLY".equals(context.get("highlightMode")) || "AUTO".equals(context.get("highlightMode")))
            || !(context.get("maxCapturesPerTurn") instanceof Integer count) || count < 1 || count > 5
            || !(context.get("fullPageEnabled") instanceof Boolean))
            throw badRequest("Context 模式、来源或限值无效");
        JsonNode scope = jsonNode(context.get("captureScope")), dom = jsonNode(context.get("dom"));
        JsonNode targets = jsonNode(context.get("targets"));
        if (!validSelectors(scope) || !validSelectors(dom)
            || !Set.of("allow", "deny", "allowViewport").containsAll(jsonKeys(scope))
            || !Set.of("allow", "deny", "excludePassword").containsAll(jsonKeys(dom))
            || scope.has("allowViewport") && !scope.path("allowViewport").isBoolean()
            || !dom.path("excludePassword").asBoolean()
            || !targets.isArray() || targets.size() > 20)
            throw badRequest("Context 采集范围无效");
        Set<String> keys = new HashSet<>();
        for (JsonNode target : targets)
            if (!target.isObject() || target.size() != 2 || !target.path("key").isTextual()
                || !target.path("key").asText().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                || !keys.add(target.path("key").asText()) || !target.path("selector").isTextual()
                || target.path("selector").asText().isBlank() || target.path("selector").asText().length() > 200)
                throw badRequest("Context 目标无效");
        if (sources.contains("ELEMENT") && targets.isEmpty()) throw badRequest("ELEMENT 来源需要配置目标");
        if (Boolean.TRUE.equals(context.get("fullPageEnabled"))
            && (!sources.contains("PAGE") || !scope.path("allowViewport").asBoolean()))
            throw badRequest("整页采集需要 PAGE 来源及明确视口授权");
    }

    private static Set<String> jsonKeys(JsonNode value)
    {
        Set<String> keys = new HashSet<>();
        value.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
    private static boolean validSelectors(JsonNode value)
    {
        if (!value.isObject()) return false;
        for (String name : List.of("allow", "deny"))
        {
            JsonNode list = value.path(name);
            if (!list.isArray() || list.size() > 20) return false;
            for (JsonNode item : list)
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 200) return false;
        }
        return true;
    }
    private Set<String> modes(Map<String, Object> context) { return stringSet(context.get("modes")); }
    private Set<String> sources(Map<String, Object> context) { return stringSet(context.get("sources")); }
    private static Set<String> stringSet(Object value)
    {
        if (!(value instanceof List<?> list)) return Set.of();
        Set<String> result = new HashSet<>();
        for (Object item : list) if (item instanceof String name) result.add(name); else return Set.of();
        return result;
    }
    private JsonNode jsonNode(Object value)
    {
        if (value == null) return com.fasterxml.jackson.databind.node.NullNode.instance;
        if (value instanceof String text)
        {
            try { return json.readTree(text); } catch (JsonProcessingException error) { throw badRequest("JSON 配置无效"); }
        }
        return json.valueToTree(value);
    }
    private static Object canonical(Object value)
    {
        if (value instanceof Map<?, ?> map)
        {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ApplicationServiceImpl::canonical).toList();
        return value;
    }
    private String nullableText(Map<String, ?> value) { return value.isEmpty() ? null : text(value); }
    private static Long optionalId(String value) { return blank(value) ? null : id(value); }
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
        List<String> capabilities = new ArrayList<>(List.of("avatar:read", "speak:write"));
        if ("CHAT".equals(config.get("mode"))) capabilities.add("chat:write");
        if (config.get("asrRelayVersionId") != null) capabilities.add("asr:write");
        if (jsonNode(config.get("contextPolicy")).path("enabled").asBoolean()) capabilities.add("context:capture");
        return Map.of("applicationId", Long.toString(applicationId), "configVersionId", config.get("configVersionId"),
            "versionNo", config.get("versionNo"), "configHash", config.get("configHash"),
            "capabilities", capabilities, "effectiveLimits", jsonNode(config.get("runtimeLimits")));
    }
    private Map<String, Object> statusResponse(Map<String, Object> app)
    { return Map.of("applicationId", app.get("applicationId"), "status", app.get("status"), "revision", app.get("revision")); }
    private static Map<String, Object> required(Map<String, Object> value) { if (value == null) throw forbidden("应用或配置不存在，或无权访问"); return value; }
    private static void requireAccount(long id) { if (id <= 0) throw forbidden("当前后台登录无效"); }
    private static long id(String value) { try { long id = Long.parseLong(value); if (id > 0) return id; } catch (RuntimeException ignored) { } throw badRequest("资源版本标识无效"); }
    private static long asLong(Object value) { return value instanceof Boolean b ? (b ? 1 : 0) : value instanceof Number n ? n.longValue() : Long.parseLong(value.toString()); }
    private static void requireKey(String key) { if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw badRequest("Idempotency-Key 无效"); }
    private static void requireRevision(String value, long revision) { if (blank(value)) throw new ServiceException("缺少 If-Match", HttpStatus.PRECONDITION_REQUIRED.value()); if (!Long.toString(revision).equals(value.replace("\"", "").trim())) throw new ServiceException("应用已变化，请刷新后重试", HttpStatus.PRECONDITION_FAILED.value()); }
    private String text(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException error) { throw new IllegalStateException("配置序列化失败", error); } }
    private static String scope(String value) { return hex(digest(value)); }
    private static byte[] digest(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (Exception error) { throw new IllegalStateException("SHA-256 不可用", error); } }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST.value()); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN.value()); }
    private static ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT.value()); }
    private static ServiceException unavailable(String message) { return new ServiceException(message, HttpStatus.UNPROCESSABLE_ENTITY.value()); }
    private record Config(String mode, long avatarVersionId, long voiceVersionId, Long llmRelayVersionId,
        Long asrRelayVersionId, String llmModelId, String systemPrompt, Map<String, Object> llmParameters,
        Map<String, Boolean> llmCapabilities, Map<String, Object> contextPolicy, Map<String, Object> runtimeLimits,
        List<ApplicationConfigRequest.SkillBinding> skills, String normalized) { }
}
