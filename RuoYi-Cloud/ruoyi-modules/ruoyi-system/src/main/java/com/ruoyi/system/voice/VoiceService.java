package com.ruoyi.system.voice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.exception.ServiceException;

/** Official voice candidates; private Relay voices remain intentionally out of this flow. */
@Service
public class VoiceService
{
    private static final Set<String> PARAMETER_KEYS = Set.of("speed", "rate", "pitch", "volume");
    private static final String DASHSCOPE_BEIJING = "DASHSCOPE_BEIJING";
    private static final String APPROVED_MODEL = "qwen3-tts-flash-realtime";
    private static final String APPROVED_VOICE = "Cherry";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public VoiceService(JdbcTemplate jdbc, ObjectMapper objectMapper)
    { this.jdbc = jdbc; this.objectMapper = objectMapper; }

    /** Compatibility path for postponed private Relay voices. It is intentionally not part of official discovery or preview. */
    @Transactional
    public PrivateVoiceResponse createPrivate(long accountId, PrivateVoiceInput input)
    {
        validatePrivate(input);
        long voiceId = nextId(); Instant now = Instant.now();
        jdbc.update("insert into p_voice (id,created_at,updated_at,account_id,visibility,name,description,status,revision) values (?,?,?,?, 'PRIVATE',?,?, 'DRAFT',1)",
            voiceId, now, now, accountId, input.name().trim(), blankToNull(input.description()));
        return createPrivateVersion(accountId, voiceId, input);
    }

    @Transactional
    public PrivateVoiceResponse createPrivateVersion(long accountId, long voiceId, PrivateVoiceInput input)
    {
        validatePrivate(input);
        Integer exists = jdbc.queryForObject("select count(1) from p_voice where id = ? and account_id = ? and visibility = 'PRIVATE' and status not in ('DELETING','DELETED')", Integer.class, voiceId, accountId);
        if (exists == null || exists != 1) throw forbidden("Voice 不存在或不属于当前账号");
        Integer relay = jdbc.queryForObject("select count(1) from p_relay_version v join p_relay_service s on s.id = v.relay_service_id where v.id = ? and v.account_id = ? and s.status = 'ACTIVE' and json_extract(v.capabilities,'$.tts') = true", Integer.class, input.relayVersionId(), accountId);
        if (relay == null || relay != 1) throw forbidden("Relay 版本不可用或未声明 TTS 能力");
        Integer number = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from p_voice_version where voice_id = ? for update", Integer.class, voiceId);
        long versionId = nextId(); Instant now = Instant.now();
        jdbc.update("insert into p_voice_version (id,created_at,updated_at,account_id,voice_id,version_no,service_type,relay_version_id,voice_code,language_code,model_id,parameters,created_by) values (?,?,?,?,?,?, 'RELAY',?,?,?,?,?,?)",
            versionId, now, now, accountId, voiceId, number == null ? 1 : number, input.relayVersionId(), input.voiceAlias().trim(),
            blankToNull(input.language()), blankToNull(input.modelAlias()), parameters(input.parameters()), accountId);
        return new PrivateVoiceResponse(text(voiceId), text(versionId), "DRAFT", "RELAY", input.voiceAlias().trim(), number == null ? 1 : number);
    }

    @Transactional
    public PrivateVoiceResponse publishPrivate(long accountId, long voiceId, long versionId)
    {
        Integer found = jdbc.queryForObject("select count(1) from p_voice_version v join p_voice voice on voice.id = v.voice_id where v.id = ? and v.voice_id = ? and voice.account_id = ? and v.account_id = ? and voice.visibility = 'PRIVATE'", Integer.class, versionId, voiceId, accountId, accountId);
        if (found == null || found != 1) throw forbidden("Voice 版本不存在或不属于当前账号");
        jdbc.update("update p_voice set status = 'PUBLISHED',current_version_id = ?,updated_at = utc_timestamp(3),revision = revision + 1 where id = ? and account_id = ?", versionId, voiceId, accountId);
        return readPrivate(accountId, voiceId);
    }

    public PrivateVoiceResponse readPrivate(long accountId, long voiceId)
    {
        PrivateVoiceResponse result = jdbc.query("select v.id,v.version_no,voice.status,v.service_type,v.voice_code from p_voice voice join p_voice_version v on v.id = voice.current_version_id where voice.id = ? and (voice.account_id = ? or (voice.visibility = 'OFFICIAL' and voice.status = 'PUBLISHED'))",
            rs -> rs.next() ? new PrivateVoiceResponse(text(voiceId), text(rs.getLong(1)), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(2)) : null, voiceId, accountId);
        if (result == null) throw forbidden("Voice 不存在或不可访问"); return result;
    }

    public List<OfficialServiceResponse> listAvailableOfficialServices()
    {
        return jdbc.query("select id,name,provider_code,model_id,revision from p_official_service "
                + "where capability = 'TTS' and status = 'ACTIVE' and provider_code = ? and model_id = ? order by id desc",
            (rs, row) -> new OfficialServiceResponse(text(rs.getLong(1)), rs.getString(2), rs.getString(3),
                rs.getString(4), text(rs.getLong(5)), availableAliases(rs.getString(3), rs.getString(4))), DASHSCOPE_BEIJING, APPROVED_MODEL);
    }

    public VoicePage listOfficial(Integer requestedPage, Integer requestedSize, String status, boolean publishedOnly)
    {
        int page = requestedPage == null ? 1 : requestedPage;
        int size = requestedSize == null ? 20 : requestedSize;
        if (page < 1 || size < 1 || size > 100) throw badRequest("分页参数无效");
        String filter = publishedOnly ? "voice.status = 'PUBLISHED'" : "voice.visibility = 'OFFICIAL'";
        Object[] args = new Object[0];
        if (!publishedOnly && status != null && !status.isBlank())
        {
            if (!Set.of("DRAFT", "PUBLISHED", "UNLISTED", "DISABLED").contains(status)) throw badRequest("状态筛选无效");
            filter += " and voice.status = ?";
            args = new Object[] {status};
        }
        Integer total = jdbc.queryForObject("select count(1) from p_voice voice where " + filter, Integer.class, args);
        List<VoiceSummary> items = jdbc.query("select voice.id,voice.name,voice.description,voice.status,voice.current_version_id,voice.revision,"
                + "voice.created_at,version.version_no,version.voice_code,version.language_code,version.model_id "
                + "from p_voice voice left join p_voice_version version on version.id = voice.current_version_id "
                + "where " + filter + " order by voice.created_at desc,voice.id desc limit ? offset ?",
            concat(args, size, (page - 1) * size), (rs, row) -> new VoiceSummary(text(rs.getLong(1)), rs.getString(2),
                rs.getString(3), rs.getString(4), nullableText(rs, 5), text(rs.getLong(6)), rs.getTimestamp(7).toInstant().toString(),
                rs.getObject(8) == null ? null : rs.getInt(8), rs.getString(9), rs.getString(10), rs.getString(11)));
        return new VoicePage(items, total == null ? 0 : total, page, size);
    }

    public VoiceDetail getOfficial(long voiceId)
    {
        VoiceHead head = officialHead(voiceId, false);
        List<VoiceVersion> versions = jdbc.query("select v.id,v.version_no,v.official_service_id,v.voice_code,v.language_code,v.model_id,"
                + "v.parameters,v.official_config_snapshot,v.created_at from p_voice_version v where v.voice_id = ? order by v.version_no desc",
            (rs, row) -> version(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant().toString()), voiceId);
        return new VoiceDetail(text(head.id()), head.name(), head.description(), head.status(), nullableText(head.currentVersionId()),
            text(head.revision()), head.updatedAt(), versions);
    }

    @Transactional
    public VoiceMutation createOfficialCandidate(long administratorId, OfficialVoiceInput input, String key)
    {
        validateOfficialInput(input); requireIdempotency(key);
        byte[] hash = digest("create|" + normalized(input));
        Long previous = existingIdempotentResource(administratorId, scope("official-voice:create"), key, hash);
        if (previous != null) return mutationFor(previous);
        OfficialService service = officialService(input.officialServiceId(), true);
        verifyExpectedServiceRevision(input.expectedServiceRevision(), service.revision()); verifyAlias(service, input.voiceAlias());
        long voiceId = nextId(), versionId = nextId(); Instant now = Instant.now();
        jdbc.update("insert into p_voice (id,created_at,updated_at,account_id,visibility,name,description,status,revision) "
                + "values (?,?,?,?, 'OFFICIAL',?,?, 'DRAFT',1)", voiceId, now, now, administratorId, input.name().trim(), blankToNull(input.description()));
        insertOfficialVersion(administratorId, voiceId, versionId, 1, input, service, now);
        recordIdempotency(administratorId, scope("official-voice:create"), key, hash, voiceId);
        return new VoiceMutation(text(voiceId), text(versionId), "DRAFT", "1");
    }

    @Transactional
    public VoiceMutation createOfficialVersion(long administratorId, long voiceId, OfficialVoiceInput input, String ifMatch, String key)
    {
        validateOfficialInput(input); requireIdempotency(key);
        VoiceHead voice = officialHeadForUpdate(voiceId, administratorId); verifyIfMatch(ifMatch, voice.revision());
        byte[] hash = digest("version|" + voiceId + "|" + normalized(input));
        Long previous = existingIdempotentResource(administratorId, scope("official-voice:version:" + voiceId), key, hash);
        if (previous != null) return mutationFor(voiceId, previous);
        OfficialService service = officialService(input.officialServiceId(), true);
        verifyExpectedServiceRevision(input.expectedServiceRevision(), service.revision()); verifyAlias(service, input.voiceAlias());
        Integer next = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from p_voice_version where voice_id = ? for update", Integer.class, voiceId);
        long versionId = nextId();
        insertOfficialVersion(administratorId, voiceId, versionId, next == null ? 1 : next, input, service, Instant.now());
        recordIdempotency(administratorId, scope("official-voice:version:" + voiceId), key, hash, versionId);
        return new VoiceMutation(text(voiceId), text(versionId), voice.status(), text(voice.revision()));
    }

    @Transactional
    public VoiceMutation publishOfficial(long administratorId, long voiceId, long versionId, PublishRequest request, String ifMatch, String key)
    {
        if (request == null || !request.auditionConfirmed()) throw badRequest("发布前必须确认已完成试听");
        requireIdempotency(key);
        VoiceHead voice = officialHeadForUpdate(voiceId, administratorId); verifyIfMatch(ifMatch, voice.revision());
        byte[] hash = digest("publish|" + voiceId + "|" + versionId + "|true|" + voice.revision());
        Long previous = existingIdempotentResource(administratorId, scope("official-voice:publish:" + voiceId), key, hash);
        if (previous != null) return mutationFor(voiceId, previous);
        OfficialVersionRow version = jdbc.query("select official_service_id,official_config_snapshot from p_voice_version "
                + "where id = ? and voice_id = ? and service_type = 'OFFICIAL' for update",
            rs -> rs.next() ? new OfficialVersionRow(rs.getLong(1), rs.getString(2)) : null, versionId, voiceId);
        if (version == null) throw forbidden("官方声音版本不存在");
        OfficialService service = officialService(version.officialServiceId(), true);
        Long snapRevision = snapshotRevision(version.snapshot());
        if (snapRevision == null || snapRevision.longValue() != service.revision())
            throw conflict("官方 TTS 服务配置已变化，请保存新的候选版本后再发布");
        jdbc.update("update p_voice set status = 'PUBLISHED',current_version_id = ?,updated_at = utc_timestamp(3),revision = revision + 1 "
                + "where id = ? and revision = ?", versionId, voiceId, voice.revision());
        recordIdempotency(administratorId, scope("official-voice:publish:" + voiceId), key, hash, versionId);
        return new VoiceMutation(text(voiceId), text(versionId), "PUBLISHED", text(voice.revision() + 1));
    }

    /** A VOICE_PREVIEW app is hidden from normal application management and freezes the candidate binding. */
    @Transactional
    public PreviewApplication preparePreview(long administratorId, long voiceId, long versionId, long avatarVersionId)
    {
        if (avatarVersionId <= 0) throw badRequest("试听需要已发布角色版本");
        officialHeadForUpdate(voiceId, administratorId);
        Integer candidate = jdbc.queryForObject("select count(1) from p_voice_version v join p_official_service s on s.id = v.official_service_id "
                + "where v.id = ? and v.voice_id = ? and v.service_type = 'OFFICIAL' and s.capability = 'TTS' and s.status = 'ACTIVE'",
            Integer.class, versionId, voiceId);
        if (candidate == null || candidate != 1) throw conflict("候选声音或其服务不可用");
        Integer actions = jdbc.queryForObject("select count(1) from p_avatar_version v join p_avatar a on a.id = v.avatar_id "
                + "join p_avatar_action action on action.avatar_version_id = v.id where v.id = ? and v.status = 'PUBLISHED' "
                + "and a.status in ('PUBLISHED','UNLISTED') and a.visibility = 'OFFICIAL' "
                + "and action.action_code in ('idle','speaking','listening','thinking','nod','shake_head','wave','happy')",
            Integer.class, avatarVersionId);
        if (actions == null || actions != 8) throw conflict("请选择已发布且包含完整八动作的官方角色版本");
        Long appId = jdbc.query("select id from p_application where account_id = ? and purpose = 'VOICE_PREVIEW' "
                + "and preview_voice_version_id = ? and status = 'ACTIVE' for update", rs -> rs.next() ? rs.getLong(1) : null, administratorId, versionId);
        if (appId == null)
        {
            appId = nextId(); Instant now = Instant.now();
            jdbc.update("insert into p_application (id,created_at,updated_at,account_id,purpose,preview_voice_version_id,name,status,current_policy,auth_epoch,revision) "
                    + "values (?,?,?,?, 'VOICE_PREVIEW',?,?, 'ACTIVE',cast('{}' as json),1,1)", appId, now, now, administratorId, versionId,
                "官方声音试听-" + voiceId + "-" + versionId);
        }
        Long configId = jdbc.query("select id from p_app_config where application_id = ? and avatar_version_id = ? and voice_version_id = ? "
                + "order by version_no desc limit 1", rs -> rs.next() ? rs.getLong(1) : null, appId, avatarVersionId, versionId);
        if (configId == null)
        {
            Integer number = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from p_app_config where application_id = ? for update", Integer.class, appId);
            configId = nextId(); Instant now = Instant.now();
            jdbc.update("insert into p_app_config (id,created_at,updated_at,account_id,application_id,version_no,mode,avatar_version_id,voice_version_id,"
                    + "context_policy,runtime_limits,config_hash,published_at,created_by) values (?,?,?,?,?,?, 'SPEAK_ONLY',?,?,cast('{}' as json),cast('{}' as json),?,?,?)",
                configId, now, now, administratorId, appId, number == null ? 1 : number, avatarVersionId, versionId,
                digest("voice-preview|" + appId + "|" + avatarVersionId + "|" + versionId), now, administratorId);
            jdbc.update("update p_application set current_config_id = ?,updated_at = utc_timestamp(3),revision = revision + 1 where id = ?", configId, appId);
            insertReference(administratorId, appId, "APP_CONFIG", configId);
            insertReference(administratorId, appId, "AVATAR_VERSION", avatarVersionId);
            insertReference(administratorId, appId, "VOICE_VERSION", versionId);
        }
        return new PreviewApplication(text(appId), text(configId), text(voiceId), text(versionId));
    }

    private void insertReference(long accountId, long appId, String resourceType, long resourceId)
    {
        Instant now = Instant.now();
        jdbc.update("insert ignore into p_resource_reference (id,created_at,updated_at,account_id,holder_type,holder_id,operation_id,resource_type,resource_id,state,confirmed_at) "
                + "values (?,?,?,?, 'APP_CURRENT',?,?,?,?,'CONFIRMED',?)", nextId(), now, now, accountId, appId,
            "voice-preview:" + appId, resourceType, resourceId, now);
    }

    private void insertOfficialVersion(long accountId, long voiceId, long versionId, int number, OfficialVoiceInput input, OfficialService service, Instant now)
    {
        jdbc.update("insert into p_voice_version (id,created_at,updated_at,account_id,voice_id,version_no,service_type,official_service_id,"
                + "voice_code,language_code,model_id,parameters,official_config_snapshot,created_by) values (?,?,?,?,?,?, 'OFFICIAL',?,?,?,?,?,?,?)",
            versionId, now, now, accountId, voiceId, number, service.id(), input.voiceAlias().trim(), blankToNull(input.language()),
            service.modelId(), parameters(input.parameters()), snapshot(service), accountId);
    }

    private VoiceHead officialHead(long voiceId, boolean locked)
    {
        VoiceHead value = jdbc.query("select id,name,description,status,current_version_id,revision,updated_at from p_voice where id = ? and visibility = 'OFFICIAL'" + (locked ? " for update" : ""),
            rs -> rs.next() ? new VoiceHead(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5) == null ? null : rs.getLong(5), rs.getLong(6), rs.getTimestamp(7).toInstant().toString()) : null, voiceId);
        if (value == null) throw forbidden("官方声音不存在"); return value;
    }
    private VoiceHead officialHeadForUpdate(long voiceId, long accountId)
    {
        VoiceHead value = jdbc.query("select id,name,description,status,current_version_id,revision,updated_at from p_voice "
                + "where id = ? and account_id = ? and visibility = 'OFFICIAL' and status not in ('DELETING','DELETED') for update",
            rs -> rs.next() ? new VoiceHead(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5) == null ? null : rs.getLong(5), rs.getLong(6), rs.getTimestamp(7).toInstant().toString()) : null, voiceId, accountId);
        if (value == null) throw forbidden("官方声音不存在或无权操作"); return value;
    }
    private VoiceMutation mutationFor(long voiceId) { return mutationFor(voiceId, latestVersionId(voiceId)); }
    private VoiceMutation mutationFor(long voiceId, long versionId)
    { VoiceHead voice = officialHead(voiceId, false); return new VoiceMutation(text(voiceId), text(versionId), voice.status(), text(voice.revision())); }
    private long latestVersionId(long voiceId)
    {
        Long id = jdbc.query("select id from p_voice_version where voice_id = ? order by version_no desc limit 1", rs -> rs.next() ? rs.getLong(1) : null, voiceId);
        if (id == null) throw new IllegalStateException("官方声音缺少版本"); return id;
    }
    private OfficialService officialService(long id, boolean locked)
    {
        OfficialService service = jdbc.query("select id,name,provider_code,model_id,revision from p_official_service "
                + "where id = ? and capability = 'TTS' and status = 'ACTIVE'" + (locked ? " for update" : ""),
            rs -> rs.next() ? new OfficialService(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)) : null, id);
        if (service == null) throw conflict("官方 TTS 服务不可用");
        if (!DASHSCOPE_BEIJING.equals(service.providerCode()) || !APPROVED_MODEL.equals(service.modelId())) throw conflict("当前官方 TTS 适配器不支持该服务模型");
        return service;
    }
    private void validateOfficialInput(OfficialVoiceInput input)
    {
        if (input == null || blank(input.name()) || input.name().trim().length() > 100 || input.officialServiceId() == null || input.officialServiceId() <= 0
            || blank(input.expectedServiceRevision()) || blank(input.voiceAlias()) || input.voiceAlias().trim().length() > 128 || input.parameters() == null
            || input.description() != null && input.description().length() > 1000) throw badRequest("官方声音参数无效");
        if (input.parameters().keySet().stream().anyMatch(key -> !PARAMETER_KEYS.contains(key)) || input.parameters().values().stream().anyMatch(value -> !(value instanceof Number)))
            throw badRequest("声音参数仅允许速率、音高和音量数值");
    }
    private void validatePrivate(PrivateVoiceInput input)
    {
        if (input == null || blank(input.name()) || input.name().trim().length() > 100 || input.relayVersionId() == null || input.relayVersionId() <= 0
            || blank(input.voiceAlias()) || input.voiceAlias().trim().length() > 128 || input.parameters() == null)
            throw badRequest("私有 Voice 参数无效");
        if (input.parameters().keySet().stream().anyMatch(key -> !PARAMETER_KEYS.contains(key)) || input.parameters().values().stream().anyMatch(value -> !(value instanceof Number)))
            throw badRequest("Voice 参数仅允许速率、音高和音量数值");
    }
    private void verifyAlias(OfficialService service, String alias)
    { if (!availableAliases(service.providerCode(), service.modelId()).contains(alias.trim())) throw conflict("该官方服务未声明可用音色"); }
    private static List<String> availableAliases(String provider, String model)
    { return DASHSCOPE_BEIJING.equals(provider) && APPROVED_MODEL.equals(model) ? List.of(APPROVED_VOICE) : List.of(); }
    private void verifyExpectedServiceRevision(String expected, long actual)
    { if (!Long.toString(actual).equals(expected.trim())) throw conflict("官方 TTS 服务配置已变化，请刷新后重试"); }
    private static void verifyIfMatch(String value, long actual)
    {
        if (value == null || value.isBlank()) throw new ServiceException("缺少 If-Match", HttpStatus.PRECONDITION_REQUIRED.value());
        if (!Long.toString(actual).equals(value.trim())) throw new ServiceException("声音配置已变化，请刷新后重试", HttpStatus.PRECONDITION_FAILED.value());
    }
    private void requireIdempotency(String value)
    { if (value == null || !value.matches("[A-Za-z0-9._:-]{1,64}")) throw badRequest("Idempotency-Key 无效"); }
    private Long existingIdempotentResource(long accountId, String scope, String key, byte[] hash)
    {
        Idempotency item = jdbc.query("select request_hash,resource_id from p_api_idempotency where account_id = ? and scope = ? and request_id = ? for update",
            rs -> rs.next() ? new Idempotency(rs.getBytes(1), rs.getLong(2)) : null, accountId, scope, key);
        if (item == null) return null;
        if (!Arrays.equals(item.hash(), hash)) throw conflict("同一 Idempotency-Key 的参数不同"); return item.resourceId();
    }
    private void recordIdempotency(long accountId, String scope, String key, byte[] hash, long resourceId)
    {
        Instant now = Instant.now();
        jdbc.update("insert into p_api_idempotency (id,created_at,updated_at,account_id,scope,request_id,request_hash,resource_type,resource_id,status,expires_at) "
                + "values (?,?,?,?,?,?,?,?,?,'SUCCEEDED',?)", nextId(), now, now, accountId, scope, key, hash, "VOICE", resourceId, now.plus(24, ChronoUnit.HOURS));
    }
    private VoiceVersion version(long id, int no, long serviceId, String alias, String language, String model, String parameters, String snapshot, String createdAt)
    { return new VoiceVersion(text(id), no, text(serviceId), alias, language, model, jsonObject(parameters), jsonObject(snapshot), createdAt); }
    private Long snapshotRevision(String snapshot)
    {
        try { Object value = objectMapper.readValue(snapshot, new TypeReference<Map<String, Object>>() {}).get("revision"); return value instanceof Number number ? number.longValue() : value == null ? null : Long.valueOf(value.toString()); }
        catch (Exception e) { return null; }
    }
    private Map<String, Object> jsonObject(String json)
    {
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("声音配置快照损坏", e); }
    }
    private String snapshot(OfficialService service)
    { return json(Map.of("serviceId", text(service.id()), "name", service.name(), "providerCode", service.providerCode(), "modelId", service.modelId(), "revision", text(service.revision()))); }
    private String parameters(Map<String, Object> values) { return json(new LinkedHashMap<>(values)); }
    private String json(Map<String, ?> value)
    {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw badRequest("声音配置无法序列化"); }
    }
    private String normalized(OfficialVoiceInput input)
    { return input.name().trim() + "|" + blankToNull(input.description()) + "|" + input.officialServiceId() + "|" + input.expectedServiceRevision().trim() + "|" + input.voiceAlias().trim() + "|" + blankToNull(input.language()) + "|" + parameters(input.parameters()); }
    private static String scope(String operation) { return hex(digest(operation)); }
    private static byte[] digest(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("无法生成请求摘要", e); }
    }
    private long nextId()
    {
        Long value = jdbc.queryForObject("select uuid_short()", Long.class);
        if (value == null || value <= 0) throw new IllegalStateException("无法生成 Voice 标识"); return value;
    }
    private static Object[] concat(Object[] prefix, Object... suffix)
    { Object[] result = Arrays.copyOf(prefix, prefix.length + suffix.length); System.arraycopy(suffix, 0, result, prefix.length, suffix.length); return result; }
    private static String nullableText(java.sql.ResultSet rs, int index) throws java.sql.SQLException { return rs.getObject(index) == null ? null : text(rs.getLong(index)); }
    private static String nullableText(Long value) { return value == null ? null : text(value); }
    private static String text(long value) { return Long.toString(value); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(bytes.length * 2); for (byte b : bytes) value.append(String.format("%02x", b)); return value.toString(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private static ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST.value()); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN.value()); }
    private static ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT.value()); }

    private record OfficialService(long id, String name, String providerCode, String modelId, long revision) { }
    private record VoiceHead(long id, String name, String description, String status, Long currentVersionId, long revision, String updatedAt) { }
    private record OfficialVersionRow(long officialServiceId, String snapshot) { }
    private record Idempotency(byte[] hash, long resourceId) { }
    public record OfficialVoiceInput(String name, String description, Long officialServiceId, String expectedServiceRevision, String voiceAlias, String language, String modelAlias, Map<String, Object> parameters) { }
    public record PrivateVoiceInput(String name, String description, Long relayVersionId, String voiceAlias, String language, String modelAlias, Map<String, Object> parameters) { }
    public record PrivateVoiceResponse(String voiceId, String versionId, String status, String serviceType, String voiceAlias, int versionNo) { }
    public record PublishRequest(boolean auditionConfirmed) { }
    public record OfficialServiceResponse(String serviceId, String name, String providerCode, String modelId, String revision, List<String> availableVoiceAliases) { }
    public record VoiceMutation(String voiceId, String versionId, String status, String revision) { }
    public record VoiceSummary(String voiceId, String name, String description, String status, String currentVersionId, String revision, String createdAt, Integer currentVersionNo, String voiceAlias, String language, String modelId) { }
    public record VoiceVersion(String versionId, int versionNo, String officialServiceId, String voiceAlias, String language, String modelId, Map<String, Object> parameters, Map<String, Object> serviceSnapshot, String createdAt) { }
    public record VoiceDetail(String voiceId, String name, String description, String status, String currentVersionId, String revision, String updatedAt, List<VoiceVersion> versions) { }
    public record VoicePage(List<VoiceSummary> items, int total, int pageNum, int pageSize) { }
    public record PreviewApplication(String applicationId, String configVersionId, String voiceId, String versionId) { }
}
