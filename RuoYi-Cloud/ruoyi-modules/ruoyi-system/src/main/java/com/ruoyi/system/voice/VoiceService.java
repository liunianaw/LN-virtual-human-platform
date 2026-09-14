package com.ruoyi.system.voice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;

/** Versioned Voice ledger. Parameters intentionally exclude endpoints, headers and provider secrets. */
@Service
public class VoiceService
{
    private static final Set<String> PARAMETER_KEYS = Set.of("speed", "rate", "pitch", "volume");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public VoiceService(JdbcTemplate jdbc, ObjectMapper objectMapper) { this.jdbc = jdbc; this.objectMapper = objectMapper; }

    @Transactional
    public VoiceResponse create(long accountId, VoiceRequest request)
    {
        validate(request, accountId);
        long voiceId = nextId();
        Instant now = Instant.now();
        jdbc.update("insert into p_voice (id,created_at,updated_at,account_id,visibility,name,description,status,revision) values (?,?,?,?, 'PRIVATE',?,?, 'DRAFT',1)",
                voiceId, now, now, accountId, request.name().trim(), blankToNull(request.description()));
        return createVersion(accountId, voiceId, request);
    }

    @Transactional
    public VoiceResponse createVersion(long accountId, long voiceId, VoiceRequest request)
    {
        validate(request, accountId);
        Integer exists = jdbc.queryForObject("select count(1) from p_voice where id = ? and account_id = ? and visibility = 'PRIVATE' and status not in ('DELETING','DELETED')", Integer.class, voiceId, accountId);
        if (exists == null || exists != 1) throw forbidden("Voice 不存在或不属于当前账号");
        if (!"RELAY".equals(request.serviceType())) throw forbidden("私有 Voice 只能使用已授权的 Relay TTS");
        Integer relayOk = jdbc.queryForObject("select count(1) from p_relay_version v join p_relay_service s on s.id = v.relay_service_id where v.id = ? and v.account_id = ? and s.status = 'ACTIVE' and json_extract(v.capabilities,'$.tts') = true",
                Integer.class, request.relayVersionId(), accountId);
        if (relayOk == null || relayOk != 1) throw forbidden("Relay 版本不可用或未声明 TTS 能力");
        Integer versionNo = jdbc.queryForObject("select coalesce(max(version_no),0)+1 from p_voice_version where voice_id = ? for update", Integer.class, voiceId);
        long versionId = nextId();
        Instant now = Instant.now();
        jdbc.update("insert into p_voice_version (id,created_at,updated_at,account_id,voice_id,version_no,service_type,relay_version_id,voice_code,language_code,model_id,parameters,created_by) values (?,?,?,?,?,?, 'RELAY',?,?,?,?,?,?)",
                versionId, now, now, accountId, voiceId, versionNo, request.relayVersionId(), request.voiceAlias().trim(),
                blankToNull(request.language()), blankToNull(request.modelAlias()), parameters(request.parameters()), accountId);
        return new VoiceResponse(voiceId, versionId, "DRAFT", "RELAY", request.voiceAlias().trim(), versionNo);
    }

    @Transactional
    public VoiceResponse publish(long accountId, long voiceId, long versionId)
    {
        Integer updated = jdbc.queryForObject("select count(1) from p_voice_version v join p_voice voice on voice.id = v.voice_id where v.id = ? and v.voice_id = ? and voice.account_id = ? and v.account_id = ? and voice.visibility = 'PRIVATE'",
                Integer.class, versionId, voiceId, accountId, accountId);
        if (updated == null || updated != 1) throw forbidden("Voice 版本不存在或不属于当前账号");
        jdbc.update("update p_voice set status = 'PUBLISHED', current_version_id = ?, updated_at = utc_timestamp(3), revision = revision + 1 where id = ? and account_id = ?", versionId, voiceId, accountId);
        return read(accountId, voiceId);
    }

    public VoiceResponse read(long accountId, long voiceId)
    {
        VoiceResponse response = jdbc.query("select v.id as version_id,v.version_no,voice.status,v.service_type,v.voice_code from p_voice voice join p_voice_version v on v.id = voice.current_version_id where voice.id = ? and (voice.account_id = ? or (voice.visibility = 'OFFICIAL' and voice.status = 'PUBLISHED'))",
                rs -> rs.next() ? new VoiceResponse(voiceId, rs.getLong("version_id"), rs.getString("status"), rs.getString("service_type"), rs.getString("voice_code"), rs.getInt("version_no")) : null,
                voiceId, accountId);
        if (response == null) throw forbidden("Voice 不存在或不可访问");
        return response;
    }

    private void validate(VoiceRequest request, long accountId)
    {
        if (request == null || blank(request.name()) || request.name().trim().length() > 100 || blank(request.serviceType())
                || blank(request.voiceAlias()) || request.voiceAlias().trim().length() > 128 || request.parameters() == null)
            throw new ServiceException("Voice 参数无效", HttpStatus.BAD_REQUEST.value());
        if (!"RELAY".equals(request.serviceType()) || request.relayVersionId() == null || request.relayVersionId() <= 0)
            throw new ServiceException("私有 Voice 需要有效的 Relay TTS 版本", HttpStatus.BAD_REQUEST.value());
        if (request.parameters().keySet().stream().anyMatch(key -> !PARAMETER_KEYS.contains(key)) || request.parameters().values().stream().anyMatch(value -> !(value instanceof Number)))
            throw new ServiceException("Voice 参数仅允许速率、音高和音量数值", HttpStatus.BAD_REQUEST.value());
    }

    private String parameters(Map<String, Object> parameters)
    {
        try { return objectMapper.writeValueAsString(new LinkedHashMap<>(parameters)); }
        catch (JsonProcessingException e) { throw new ServiceException("Voice 参数无法序列化", HttpStatus.BAD_REQUEST.value()); }
    }

    private long nextId()
    {
        Long value = jdbc.queryForObject("select uuid_short()", Long.class);
        if (value == null || value <= 0) throw new IllegalStateException("无法生成 Voice 标识");
        return value;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN.value()); }

    public record VoiceRequest(String name, String description, String serviceType, Long relayVersionId, String voiceAlias,
            String language, String modelAlias, Map<String, Object> parameters) { }
    public record VoiceResponse(long voiceId, long versionId, String status, String serviceType, String voiceAlias, int versionNo) { }
}
