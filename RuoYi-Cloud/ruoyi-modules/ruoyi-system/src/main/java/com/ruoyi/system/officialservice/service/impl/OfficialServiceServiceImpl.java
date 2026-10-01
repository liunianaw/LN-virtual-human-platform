package com.ruoyi.system.officialservice.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.officialservice.domain.OfficialSecret;
import com.ruoyi.system.officialservice.domain.OfficialService;
import com.ruoyi.system.officialservice.domain.OfficialIdempotency;
import com.ruoyi.system.officialservice.dto.OfficialServiceCredentialRequest;
import com.ruoyi.system.officialservice.dto.OfficialServiceInput;
import com.ruoyi.system.officialservice.dto.OfficialServiceStatusRequest;
import com.ruoyi.system.officialservice.mapper.OfficialServiceMapper;
import com.ruoyi.system.officialservice.service.IOfficialServiceService;
import com.ruoyi.system.officialservice.service.OfficialSecretCrypto;

/** Administrator service configuration and its fail-closed protected resolver. */
@Service
public class OfficialServiceServiceImpl implements IOfficialServiceService
{
    private static final String IMAGE = "DASHSCOPE_IMAGE", REALTIME = "DASHSCOPE_BEIJING";
    private static final String IMAGE_MODEL = "qwen-image-3.0-pro", TTS_MODEL = "qwen3-tts-flash-realtime",
        ASR_MODEL = "qwen3-asr-flash";
    private static final Set<String> IMAGE_PARAMETERS = Set.of("n", "size", "prompt_extend", "watermark", "seed");
    private static final Set<String> TTS_PARAMETERS = Set.of("speed", "rate", "pitch", "volume");
    private final OfficialServiceMapper mapper;
    private final OfficialSecretCrypto crypto;
    private final ObjectMapper json;
    public OfficialServiceServiceImpl(OfficialServiceMapper mapper, OfficialSecretCrypto crypto, ObjectMapper json)
    { this.mapper = mapper; this.crypto = crypto; this.json = json; }

    @Override public Page list(String capability, String status, Integer pageNum, Integer pageSize)
    {
        int page = pageNum == null ? 1 : pageNum, size = pageSize == null ? 20 : pageSize;
        if (page < 1 || size < 1 || size > 100 || invalidFilter(capability, status)) throw bad("分页或筛选条件无效");
        return new Page(mapper.selectPage(capability, status, (page - 1) * size, size).stream().map(this::view).toList(), mapper.countPage(capability, status), page, size);
    }
    @Override public View read(long serviceId) { return view(require(serviceId)); }

    @Override @Transactional public View create(long administratorId, OfficialServiceInput input, String key)
    {
        requireKey(key); validate(input); if (input.getSecretId() == null || mapper.selectSecret(input.getSecretId()) == null) throw bad("创建服务必须选择有效的官方凭证引用");
        byte[] hash = digest("create|" + write(input.getParameters()) + "|" + input.getName().trim() + "|" + input.getCapability() + "|" + input.getProviderCode() + "|" + input.getEndpoint().trim() + "|" + input.getModelId().trim() + "|" + input.getSecretId());
        Long duplicate = duplicate(administratorId, "official-service:create", key, hash); if (duplicate != null) return view(require(duplicate));
        OfficialService service = build(mapper.nextId(), administratorId, input, input.getSecretId(), "DISABLED", 1L); mapper.insert(service); complete(administratorId, "official-service:create", key, hash, service.getId()); return view(service);
    }
    @Override @Transactional public View update(long administratorId, long serviceId, OfficialServiceInput input, String ifMatch, String key)
    {
        requireKey(key); validate(input); OfficialService current = requireForUpdate(serviceId);
        byte[] hash = digest("update|" + serviceId + "|" + ifMatch + "|" + write(input.getParameters()) + "|" + input.getName().trim() + "|" + input.getCapability() + "|" + input.getProviderCode() + "|" + input.getEndpoint().trim() + "|" + input.getModelId().trim() + "|" + input.getSecretId());
        Long duplicate = duplicate(administratorId, "official-service:update:" + serviceId, key, hash); if (duplicate != null) return view(require(duplicate));
        requireMatch(ifMatch, current);
        Long secretId = input.getSecretId() == null ? current.getSecretId() : input.getSecretId(); if (mapper.selectSecret(secretId) == null) throw bad("官方凭证引用不存在");
        OfficialService changed = build(current.getId(), administratorId, input, secretId, current.getStatus(), current.getRevision() + 1); if (mapper.update(changed) != 1) throw stale(); complete(administratorId, "official-service:update:" + serviceId, key, hash, serviceId); return view(changed);
    }
    @Override @Transactional public View replaceCredential(long administratorId, long serviceId, OfficialServiceCredentialRequest request, String ifMatch, String key)
    {
        if (request == null) throw bad("凭证参数无效"); requireKey(key); OfficialService current = requireForUpdate(serviceId);
        byte[] hash = crypto.keyedDigest("credential|" + serviceId + "|" + ifMatch + "|" + request.getProviderKey());
        Long duplicate = duplicate(administratorId, "official-service:credential:" + serviceId, key, hash); if (duplicate != null) return view(require(duplicate));
        requireMatch(ifMatch, current);
        long secretId = id(); OfficialSecret secret = crypto.encrypt(secretId, request.getProviderKey().trim()); mapper.insertSecret(secret, administratorId, current.getName() + " 凭证", suffix(request.getProviderKey()));
        current.setSecretId(secretId); current.setRevision(current.getRevision() + 1); if (mapper.update(current) != 1) throw stale(); complete(administratorId, "official-service:credential:" + serviceId, key, hash, serviceId); return view(current);
    }
    @Override public Check check(long serviceId, String ifMatch, String key)
    {
        requireKey(key); OfficialService service = require(serviceId); requireMatch(ifMatch, service); try { validateStored(service); crypto.decrypt(mapper.selectSecret(service.getSecretId())); return new Check(text(service.getRevision()), true, false, List.of()); }
        catch (ServiceException exception) { return new Check(text(service.getRevision()), false, false, List.of(exception.getMessage())); }
    }
    @Override @Transactional public View changeStatus(long serviceId, OfficialServiceStatusRequest request, String ifMatch, String key)
    {
        requireKey(key); if (request == null || request.getReason() == null || request.getReason().isBlank()) throw bad("状态原因无效"); OfficialService service = requireForUpdate(serviceId);
        byte[] hash = digest("status|" + serviceId + "|" + ifMatch + "|" + request.getStatus() + "|" + request.getReason().trim());
        Long duplicate = duplicate(service.getAccountId(), "official-service:status:" + serviceId, key, hash); if (duplicate != null) return view(require(duplicate));
        requireMatch(ifMatch, service);
        if ("ACTIVE".equals(request.getStatus())) { validateStored(service); crypto.decrypt(mapper.selectSecret(service.getSecretId())); }
        long revision = service.getRevision() + 1; if (mapper.updateStatus(serviceId, request.getStatus(), revision) != 1) throw stale(); service.setStatus(request.getStatus()); service.setRevision(revision); complete(service.getAccountId(), "official-service:status:" + serviceId, key, hash, serviceId); return view(service);
    }
    @Override public ResolvedService resolve(long serviceId, long expectedRevision, String purpose, Long taskId, Long voiceVersionId)
    {
        if (!"AVATAR_GENERATION".equals(purpose) && !"TTS".equals(purpose)) throw bad("内部解析用途无效");
        if (("AVATAR_GENERATION".equals(purpose) && (taskId == null || voiceVersionId != null)) || ("TTS".equals(purpose) && (voiceVersionId == null || taskId != null))) throw bad("内部解析绑定无效");
        int bindings = "AVATAR_GENERATION".equals(purpose) ? mapper.countGenerationBinding(serviceId, expectedRevision, taskId) : mapper.countVoiceBinding(serviceId, expectedRevision, voiceVersionId);
        if (bindings != 1) throw new ServiceException("官方服务与冻结任务或声音版本不匹配", HttpStatus.CONFLICT.value());
        OfficialService service = mapper.selectResolvable(serviceId, expectedRevision, purpose); if (service == null) throw new ServiceException("官方服务已停用或修订不匹配", HttpStatus.CONFLICT.value());
        validateStored(service);
        return new ResolvedService(service.getProviderCode(), service.getEndpoint(), service.getModelId(), parameters(service.getParameters()), crypto.decrypt(mapper.selectSecret(service.getSecretId())));
    }

    @Override public DefaultResolvedService resolveDefault(String purpose)
    {
        if (!"ASR".equals(purpose)) throw bad("内部默认服务用途无效");
        OfficialService service = mapper.selectDefaultResolvable("ASR");
        if (service == null) throw new ServiceException("默认官方 ASR 未配置", HttpStatus.SERVICE_UNAVAILABLE.value());
        validateStored(service);
        return new DefaultResolvedService(text(service.getId()), text(service.getRevision()), service.getProviderCode(),
            service.getEndpoint(), service.getModelId(), parameters(service.getParameters()),
            crypto.decrypt(mapper.selectSecret(service.getSecretId())));
    }

    private OfficialService build(long id, long accountId, OfficialServiceInput input, long secretId, String status, long revision)
    { OfficialService service = new OfficialService(); service.setId(id); service.setAccountId(accountId); service.setName(input.getName().trim()); service.setCapability(input.getCapability()); service.setProviderCode(input.getProviderCode()); service.setEndpoint(input.getEndpoint().trim()); service.setModelId(input.getModelId().trim()); service.setParameters(write(input.getParameters())); service.setSecretId(secretId); service.setStatus(status); service.setRevision(revision); service.setCreatedAt(Instant.now()); service.setUpdatedAt(Instant.now()); return service; }
    private void validate(OfficialServiceInput input) { if (input == null) throw bad("服务参数无效"); validateStored(build(1L, 1L, input, input.getSecretId() == null ? 1L : input.getSecretId(), "DISABLED", 1L)); }
    private void validateStored(OfficialService service)
    {
        try
        {
            URI endpoint = URI.create(service.getEndpoint()); boolean image = "AVATAR_GENERATION".equals(service.getCapability());
            boolean asr = "ASR".equals(service.getCapability());
            if (endpoint.getUserInfo() != null || endpoint.getPort() > 0 || endpoint.getQuery() != null || endpoint.getFragment() != null || !"dashscope.aliyuncs.com".equals(endpoint.getHost())) throw bad("服务地址不在官方白名单");
            if (image && (!IMAGE.equals(service.getProviderCode()) || !IMAGE_MODEL.equals(service.getModelId()) || !"https".equalsIgnoreCase(endpoint.getScheme()))) throw bad("图像服务适配器或模型不受支持");
            if (asr && (!REALTIME.equals(service.getProviderCode()) || !ASR_MODEL.equals(service.getModelId()) || !"https".equalsIgnoreCase(endpoint.getScheme()) || !"/compatible-mode/v1/chat/completions".equals(endpoint.getPath()))) throw bad("ASR 服务适配器或模型不受支持");
            if (!image && !asr && (!REALTIME.equals(service.getProviderCode()) || !TTS_MODEL.equals(service.getModelId()) || !"wss".equalsIgnoreCase(endpoint.getScheme()))) throw bad("TTS 服务适配器或模型不受支持");
            Map<String, Object> values = parameters(service.getParameters()); Set<String> allowed = image ? IMAGE_PARAMETERS : asr ? Set.of("language") : TTS_PARAMETERS;
            if (!allowed.containsAll(values.keySet())) throw bad("服务参数不在适配器白名单");
        }
        catch (IllegalArgumentException e) { throw bad("服务地址或参数无效"); }
    }
    private View view(OfficialService service) { return new View(text(service.getId()), service.getName(), service.getCapability(), service.getProviderCode(), service.getEndpoint(), service.getModelId(), parameters(service.getParameters()), service.getStatus(), text(service.getRevision()), Boolean.TRUE.equals(service.getCredentialConfigured()), text(service.getSecretId())); }
    private OfficialService require(long id) { OfficialService item = mapper.selectById(id); if (item == null) throw new ServiceException("官方服务不存在", 404); return item; }
    private OfficialService requireForUpdate(long id) { OfficialService item = mapper.selectByIdForUpdate(id); if (item == null) throw new ServiceException("官方服务不存在", 404); return item; }
    private Map<String, Object> parameters(String value) { try { return json.readValue(value, new TypeReference<Map<String, Object>>() { }); } catch (Exception e) { throw bad("服务参数存储损坏"); } }
    private String write(Map<String, Object> value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw bad("服务参数无法序列化"); } }
    private long id() { Long value = mapper.nextId(); if (value == null || value <= 0) throw new ServiceException("无法生成服务标识"); return value; }
    private Long duplicate(long accountId, String action, String key, byte[] hash) { OfficialIdempotency item = mapper.selectIdempotencyForUpdate(accountId, scope(action), key); if (item == null) return null; if (!Arrays.equals(hash, item.getRequestHash()) || item.getResourceId() == null) throw new ServiceException("同一 Idempotency-Key 的参数不同", 409); return item.getResourceId(); }
    private void complete(long accountId, String action, String key, byte[] hash, long resourceId) { mapper.insertIdempotency(id(), accountId, scope(action), key, hash, resourceId); }
    private static byte[] digest(String text) { try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new IllegalStateException("请求摘要不可用", e); } }
    private static String scope(String action) { byte[] bytes = digest(action); StringBuilder value = new StringBuilder(64); for (byte item : bytes) value.append(String.format("%02x", item)); return value.toString(); }
    private static boolean invalidFilter(String capability, String status) { return capability != null && !Set.of("AVATAR_GENERATION", "TTS", "ASR").contains(capability) || status != null && !Set.of("ACTIVE", "DISABLED").contains(status); }
    private static void requireKey(String key) { if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) throw bad("Idempotency-Key 无效"); }
    private static void requireMatch(String value, OfficialService service) { if (value == null || value.isBlank()) throw new ServiceException("缺少 If-Match", 428); if (!text(service.getRevision()).equals(value.trim())) throw stale(); }
    private static String suffix(String key) { String value = key.trim(); return value.length() <= 4 ? value : value.substring(value.length() - 4); }
    private static String text(long value) { return Long.toString(value); }
    private static ServiceException bad(String message) { return new ServiceException(message, 400); }
    private static ServiceException stale() { return new ServiceException("服务配置已变化，请刷新后重试", 412); }
}
