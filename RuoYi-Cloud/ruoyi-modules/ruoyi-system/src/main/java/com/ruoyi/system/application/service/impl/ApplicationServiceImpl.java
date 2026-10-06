package com.ruoyi.system.application.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.application.dto.ApplicationConfigRequest;
import com.ruoyi.system.application.dto.ApplicationStatusRequest;
import com.ruoyi.system.application.dto.CreateApplicationRequest;
import com.ruoyi.system.application.mapper.ApplicationMapper;
import com.ruoyi.system.application.service.IApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the one current configuration of a developer Application. */
@Service
public class ApplicationServiceImpl implements IApplicationService
{
    private final ApplicationMapper mapper;
    private final ObjectMapper json;

    public ApplicationServiceImpl(ApplicationMapper mapper, ObjectMapper json)
    { this.mapper = mapper; this.json = json; }

    @Override
    public Map<String, Object> list(long accountId, Integer requestedPage, Integer requestedSize, String requestedStatus)
    {
        requireDeveloper(accountId);
        int page = requestedPage == null ? 1 : requestedPage, size = requestedSize == null ? 20 : requestedSize;
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE) throw bad("分页参数无效");
        String status = blank(requestedStatus) ? null : requestedStatus.trim();
        if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status)) throw bad("应用状态筛选无效");
        return Map.of("items", mapper.selectApplications(accountId, status, (page - 1) * size, size),
            "total", mapper.countApplications(accountId, status), "pageNum", page, "pageSize", size);
    }

    @Override
    public Map<String, Object> search(long accountId, Integer requestedPage, Integer requestedSize, String requestedStatus, String keyword)
    {
        if (blank(keyword)) return list(accountId, requestedPage, requestedSize, requestedStatus);
        requireDeveloper(accountId);
        int page = requestedPage == null ? 1 : requestedPage, size = requestedSize == null ? 20 : requestedSize;
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE) throw bad("分页参数无效");
        String status = blank(requestedStatus) ? null : requestedStatus.trim();
        if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status)) throw bad("应用状态筛选无效");
        String filter = keyword.trim();
        if (filter.length() > 100) throw bad("搜索名称不能超过 100 字符");
        return Map.of("items", mapper.searchApplications(accountId, status, filter, (page - 1) * size, size),
            "total", mapper.countSearchApplications(accountId, status, filter), "pageNum", page, "pageSize", size);
    }

    @Override
    public Map<String, Object> detail(long accountId, long applicationId)
    {
        requireDeveloper(accountId);
        Map<String, Object> result = new LinkedHashMap<>(required(mapper.selectApplication(accountId, applicationId)));
        result.put("skills", mapper.selectApplicationSkills(applicationId));
        return result;
    }

    @Override
    public Map<String, Object> choices(long accountId, long applicationId)
    {
        requireDeveloper(accountId);
        required(mapper.selectApplication(accountId, applicationId));
        return Map.of("avatars", mapper.selectAvatarChoices(accountId), "voices", mapper.selectVoiceChoices(),
            "skills", mapper.selectSkillChoices(accountId));
    }

    @Override
    @Transactional
    public Map<String, Object> create(long accountId, CreateApplicationRequest request, String key)
    {
        requireDeveloper(accountId); requireKey(key);
        if (request == null || blank(request.name())) throw bad("应用名称不能为空");
        String name = request.name().trim(), description = nullable(request.description());
        if (name.length() > 100 || description != null && description.length() > 1000) throw bad("应用基本信息无效");
        byte[] hash = digest("create|" + name + "|" + (description == null ? "" : description));
        long existing = existing(accountId, scope("application:create"), key, hash);
        if (existing > 0) return detail(accountId, existing);
        long id = mapper.nextId(); Instant now = Instant.now();
        mapper.insertApplication(id, accountId, name, description, now);
        remember(accountId, scope("application:create"), key, hash, id, now);
        return detail(accountId, id);
    }

    @Override
    @Transactional
    public Map<String, Object> update(long accountId, long applicationId, ApplicationConfigRequest request,
        String ifMatch, String key)
    {
        requireDeveloper(accountId); requireKey(key);
        Current input = current(request);
        byte[] hash = digest("update|" + applicationId + "|" + encode(input));
        long existing = existing(accountId, scope("application:update:" + applicationId), key, hash);
        if (existing > 0) return detail(accountId, applicationId);
        Map<String, Object> application = required(mapper.selectApplicationForUpdate(accountId, applicationId));
        long revision = requireRevision(ifMatch, number(application.get("revision")));
        if (mapper.countAvailableAvatar(accountId, input.avatarId()) != 1) throw unavailable("Avatar 不可用于新 Session");
        if (mapper.countAvailableVoice(input.voiceId()) != 1) throw unavailable("仅可选择已发布的官方声音");
        Set<String> toolNames = new HashSet<>();
        for (ApplicationConfigRequest.SkillBinding binding : input.skills())
        {
            Map<String, Object> skill = mapper.selectSkillBindingForUpdate(accountId, bindingId(binding));
            if (skill == null) throw unavailable("Skill 不可用于新 Session");
            Object toolName = skill.get("toolName");
            if (toolName != null && !toolNames.add(toolName.toString())) throw conflict("同一 Application 的 Tool 名称重复");
        }
        if (mapper.updateCurrent(applicationId, input.name(), input.description(), input.avatarId(), input.voiceId(),
            input.systemPrompt(), revision) != 1) throw precondition("应用配置已变化，请刷新后重试");
        mapper.deleteApplicationSkills(applicationId);
        Instant now = Instant.now();
        for (ApplicationConfigRequest.SkillBinding binding : input.skills())
            mapper.insertApplicationSkill(mapper.nextId(), accountId, applicationId, bindingId(binding), binding.sortOrder(), now);
        mapper.releaseCurrentReferences(accountId, applicationId);
        Long avatarVersion = mapper.selectAvatarVersion(accountId, input.avatarId());
        Long voiceVersion = mapper.selectVoiceVersion(input.voiceId());
        if (avatarVersion == null || voiceVersion == null) throw unavailable("资源当前版本不可用");
        String operation = "application:" + applicationId + ":" + key;
        reference(accountId, applicationId, operation, "AVATAR_VERSION", avatarVersion, now);
        reference(accountId, applicationId, operation, "VOICE_VERSION", voiceVersion, now);
        Long fallback=mapper.selectVoiceFallback(voiceVersion);
        if(fallback!=null) reference(accountId,applicationId,operation,"VOICE_VERSION",fallback,now);
        remember(accountId, scope("application:update:" + applicationId), key, hash, applicationId, now);
        return detail(accountId, applicationId);
    }

    @Override
    @Transactional
    public Map<String, Object> changeStatus(long accountId, long applicationId, ApplicationStatusRequest request,
        String ifMatch, String key)
    {
        requireDeveloper(accountId); requireKey(key);
        if (request == null || !Set.of("ACTIVE", "DISABLED").contains(request.status()) || blank(request.reason()))
            throw bad("应用状态参数无效");
        byte[] hash = digest("status|" + applicationId + "|" + request.status() + "|" + request.reason().trim());
        long existing = existing(accountId, scope("application:status:" + applicationId), key, hash);
        if (existing > 0) return detail(accountId, applicationId);
        Map<String, Object> application = required(mapper.selectApplicationForUpdate(accountId, applicationId));
        long revision = requireRevision(ifMatch, number(application.get("revision")));
        if (request.status().equals(application.get("status")))
        {
            remember(accountId, scope("application:status:" + applicationId), key, hash, applicationId, Instant.now());
            return detail(accountId, applicationId);
        }
        if ("ACTIVE".equals(request.status()) && mapper.countCurrentConfigAvailable(applicationId) != 1)
            throw conflict("重新启用前需保存仍然可用的当前配置");
        if (mapper.updateStatus(applicationId, request.status(), revision) != 1)
            throw precondition("应用状态已变化，请刷新后重试");
        Instant now = Instant.now(); String eventId = UUID.randomUUID().toString().replace("-", "");
        mapper.insertOutbox(mapper.nextId(), accountId, eventId, Long.toString(applicationId),
            encode(Map.of("applicationId", Long.toString(applicationId), "status", request.status(),
                "reason", request.reason().trim())), now);
        remember(accountId, scope("application:status:" + applicationId), key, hash, applicationId, now);
        return detail(accountId, applicationId);
    }

    private Current current(ApplicationConfigRequest request)
    {
        if (request == null || blank(request.name())) throw bad("应用配置无效");
        String name = request.name().trim(), description = nullable(request.description()), prompt = nullable(request.systemPrompt());
        if (name.length() > 100 || description != null && description.length() > 1000
            || prompt != null && prompt.length() > 32768) throw bad("应用配置字段超出范围");
        long avatar = id(request.avatarId()), voice = id(request.voiceId());
        List<ApplicationConfigRequest.SkillBinding> skills = request.skills() == null ? List.of() : request.skills();
        Set<Long> ids = new HashSet<>(); Set<Integer> orders = new HashSet<>();
        for (ApplicationConfigRequest.SkillBinding binding : skills)
            if (binding == null || !ids.add(bindingId(binding)) || binding.sortOrder() < 0 || !orders.add(binding.sortOrder()))
                throw bad("Skill 绑定重复或顺序无效");
        List<ApplicationConfigRequest.SkillBinding> ordered = skills.stream()
            .sorted(java.util.Comparator.comparingInt(ApplicationConfigRequest.SkillBinding::sortOrder)).toList();
        return new Current(name, description, avatar, voice, prompt, ordered);
    }

    private void remember(long accountId, String operation, String key, byte[] hash, long resourceId, Instant now)
    { mapper.insertIdempotency(mapper.nextId(), accountId, operation, key, hash, resourceId, now.plus(24, ChronoUnit.HOURS)); }

    private long existing(long accountId, String operation, String key, byte[] hash)
    {
        Map<String, Object> row = mapper.selectIdempotencyForUpdate(accountId, operation, key);
        if (row == null) return 0;
        if (!Arrays.equals(hash, (byte[]) row.get("requestHash"))) throw conflict("同一 Idempotency-Key 的参数不同");
        return number(row.get("resourceId"));
    }

    private void reference(long accountId, long applicationId, String operation, String type, long resourceId, Instant now)
    { mapper.insertCurrentReference(mapper.nextId(), accountId, applicationId, operation, type, resourceId, now); }

    private static void requireDeveloper(long accountId)
    {
        if (accountId <= 0) throw bad("账号无效");
        var login = SecurityUtils.getLoginUser();
        if (login != null && (!Long.valueOf(accountId).equals(login.getUserid()) || SecurityUtils.isAdmin()
            || login.getRoles() == null || !login.getRoles().contains("developer")))
            throw forbidden("仅开发者账号可管理 Application");
    }

    private long requireRevision(String value, long actual)
    {
        if (blank(value)) throw new ServiceException("缺少 If-Match", 428);
        try { if (Long.parseLong(value) != actual) throw precondition("应用配置已变化，请刷新后重试"); }
        catch (NumberFormatException error) { throw precondition("If-Match 无效"); }
        return actual;
    }

    private static void requireKey(String key)
    { if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw bad("Idempotency-Key 无效"); }
    private static Map<String, Object> required(Map<String, Object> value)
    { if (value == null) throw new ServiceException("Application 不存在", 404); return value; }
    private String encode(Object value)
    { try { return json.writeValueAsString(value); } catch (Exception error) { throw bad("应用配置无法编码"); } }
    private static byte[] digest(String value)
    { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
      catch (Exception error) { throw new IllegalStateException(error); } }
    private static String scope(String value) { return java.util.HexFormat.of().formatHex(digest(value)); }
    private static long bindingId(ApplicationConfigRequest.SkillBinding value) { return id(value.skillId()); }
    private static long id(String value)
    { try { long id = Long.parseLong(value); if (id <= 0) throw new NumberFormatException(); return id; }
      catch (Exception error) { throw bad("资源 ID 无效"); } }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString()); }
    private static String nullable(String value) { return blank(value) ? null : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ServiceException bad(String message) { return new ServiceException(message, 400); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, 403); }
    private static ServiceException conflict(String message) { return new ServiceException(message, 409); }
    private static ServiceException precondition(String message) { return new ServiceException(message, 412); }
    private static ServiceException unavailable(String message) { return new ServiceException(message, HttpStatus.CONFLICT.value()); }

    private record Current(String name, String description, long avatarId, long voiceId, String systemPrompt,
        List<ApplicationConfigRequest.SkillBinding> skills) { }
}
