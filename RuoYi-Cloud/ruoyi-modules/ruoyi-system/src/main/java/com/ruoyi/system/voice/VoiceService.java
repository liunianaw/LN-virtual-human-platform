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

/** Administrator-managed official voice candidates. */
@Service
public class VoiceService
{
    private static final Set<String> PARAMETER_KEYS = Set.of("speed", "rate", "pitch", "volume");
    private static final String DASHSCOPE_BEIJING = "DASHSCOPE_BEIJING";
    private static final String APPROVED_MODEL = "qwen3-tts-flash-realtime";
    private static final String APPROVED_VOICE = "Cherry";
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OfficialVoiceAuditionClient auditions;
    private final com.ruoyi.system.operations.OperationsService operations;

    public VoiceService(JdbcTemplate jdbc, ObjectMapper objectMapper, OfficialVoiceAuditionClient auditions,
        com.ruoyi.system.operations.OperationsService operations)
    { this.jdbc = jdbc; this.objectMapper = objectMapper; this.auditions = auditions; this.operations = operations; }

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
        String filter = publishedOnly
            ? "voice.visibility = 'OFFICIAL' and voice.status = 'PUBLISHED' and exists ("
                + "select 1 from p_voice_version v join p_official_service s on s.id = v.official_service_id "
                + "where v.id = voice.current_version_id and v.service_type = 'OFFICIAL' and s.status = 'ACTIVE' and s.capability = 'TTS')"
            : "voice.visibility = 'OFFICIAL'";
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
        Integer completedAuditions = jdbc.queryForObject("select count(*) from p_api_idempotency " +
            "where account_id=? and scope=? and resource_id=? and status='SUCCEEDED'", Integer.class,
            administratorId, scope("official-voice:audition:" + voiceId + ":" + versionId), versionId);
        if (completedAuditions == null || completedAuditions == 0) throw conflict("发布前必须完成该版本官方试听");
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

    public byte[] audition(long administratorId, long voiceId, long versionId, String text, String key)
    {
        requireIdempotency(key);
        if (!com.ruoyi.common.security.utils.SecurityUtils.isAdmin()) throw forbidden("仅管理员可试听官方声音");
        if (text == null || text.isBlank() || text.length() > 200) throw badRequest("试听文本限 1～200 字符");
        VoiceHead head = officialHead(voiceId, false);
        if (Set.of("DISABLED", "DELETING", "DELETED").contains(head.status())) throw conflict("官方声音不可用");
        Map<String, Object> version = jdbc.query("select official_service_id,voice_code,official_config_snapshot " +
            "from p_voice_version where voice_id=? and id=? and service_type='OFFICIAL'",
            rs -> rs.next() ? Map.of("serviceId", rs.getLong(1), "alias", rs.getString(2),
                "snapshot", rs.getString(3)) : null, voiceId, versionId);
        if (version == null) throw forbidden("官方声音版本不存在");
        long serviceId = (Long) version.get("serviceId");
        Long revision = snapshotRevision((String) version.get("snapshot"));
        OfficialService service = officialService(serviceId, false);
        if (revision == null || revision != service.revision()) throw conflict("官方服务已变更，请保存新候选");
        String auditionScope = scope("official-voice:audition:" + voiceId + ":" + versionId);
        byte[] hash = digest(text + "|" + revision);
        int claimed = jdbc.update("insert ignore into p_api_idempotency " +
            "(id,created_at,updated_at,account_id,scope,request_id,request_hash,resource_type,resource_id,status,expires_at) " +
            "values (uuid_short(),utc_timestamp(3),utc_timestamp(3),?,?,?,?,'VOICE_VERSION',?,'PROCESSING',date_add(utc_timestamp(3),interval 1 day))",
            administratorId, auditionScope, key, hash, versionId);
        if (claimed != 1) throw conflict("该试听请求已受理，请勿重复付费提交");
        String operation = "audition-" + java.util.UUID.randomUUID();
        auditionFact(administratorId, operation, "STARTED", text.length(), null);
        try
        {
            byte[] audio = auditions.audition(versionId, serviceId, revision, (String) version.get("alias"), text);
            jdbc.update("update p_api_idempotency set status='SUCCEEDED',updated_at=utc_timestamp(3) " +
                "where account_id=? and scope=? and request_id=? and status='PROCESSING'", administratorId, auditionScope, key);
            auditionFact(administratorId, operation, "SUCCEEDED", text.length(), null);
            return audio;
        }
        catch (Exception error)
        {
            jdbc.update("update p_api_idempotency set status='FAILED',updated_at=utc_timestamp(3) " +
                "where account_id=? and scope=? and request_id=? and status='PROCESSING'", administratorId, auditionScope, key);
            auditionFact(administratorId, operation, "UNKNOWN", text.length(), "AUDITION_UNAVAILABLE");
            throw error;
        }
    }

    private void auditionFact(long accountId, String operation, String status, int characters, String code)
    {
        operations.accept(new com.ruoyi.system.operations.CallFactEvent(operation + "-" + status, operation,
            accountId, "TTS", status, null, null, null, null,
            new com.ruoyi.system.operations.CallFactEvent.Usage((long) characters, null, null, false),
            null, null, "UNKNOWN", code));
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
    public record PublishRequest(boolean auditionConfirmed) { }
    public record OfficialServiceResponse(String serviceId, String name, String providerCode, String modelId, String revision, List<String> availableVoiceAliases) { }
    public record VoiceMutation(String voiceId, String versionId, String status, String revision) { }
    public record VoiceSummary(String voiceId, String name, String description, String status, String currentVersionId, String revision, String createdAt, Integer currentVersionNo, String voiceAlias, String language, String modelId) { }
    public record VoiceVersion(String versionId, int versionNo, String officialServiceId, String voiceAlias, String language, String modelId, Map<String, Object> parameters, Map<String, Object> serviceSnapshot, String createdAt) { }
    public record VoiceDetail(String voiceId, String name, String description, String status, String currentVersionId, String revision, String updatedAt, List<VoiceVersion> versions) { }
    public record VoicePage(List<VoiceSummary> items, int total, int pageNum, int pageSize) { }
}
