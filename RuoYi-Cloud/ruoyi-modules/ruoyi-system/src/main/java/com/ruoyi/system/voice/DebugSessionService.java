package com.ruoyi.system.voice;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.api.model.LoginUser;

/** Derives DEBUG identity from the current console login and freezes the published configuration. */
@Service
public class DebugSessionService
{
    private final JdbcTemplate jdbc;
    private final SessionDebugClient sessionClient;
    private final ConsoleLoginRegistry logins;
    private final ConcurrentHashMap<Long, Registration> sessions = new ConcurrentHashMap<>();

    public DebugSessionService(JdbcTemplate jdbc, SessionDebugClient sessionClient, ConsoleLoginRegistry logins)
    {
        this.jdbc = jdbc; this.sessionClient = sessionClient; this.logins = logins;
    }

    public DebugSession create(long accountId, LoginUser login, long applicationId, String requestId)
    {
        if (blank(requestId) || requestId.length() > 64) throw new ServiceException("Idempotency-Key 无效", HttpStatus.BAD_REQUEST.value());
        Binding binding = binding(accountId, applicationId, false);
        String issuerRef = logins.register(login);
        SessionDebugClient.CreatedSession created = sessionClient.create(accountId, applicationId, binding.configVersionId(), requestId);
        if (created.sessionId() <= 0 || created.applicationId() != applicationId || created.configVersionId() != binding.configVersionId())
            throw new ServiceException("会话服务返回无效 DEBUG Session", HttpStatus.BAD_GATEWAY.value());
        recordSessionReferences(accountId, created.sessionId(), binding, requestId);
        sessions.put(created.sessionId(), new Registration(accountId, applicationId, binding, issuerRef));
        return new DebugSession(created.sessionId(), applicationId, binding.configVersionId());
    }

    /** Only the official-voice controller invokes this branch after it has created a VOICE_PREVIEW configuration. */
    public DebugSession createPreview(long accountId, LoginUser login, long applicationId, String requestId)
    {
        if (blank(requestId) || requestId.length() > 64) throw new ServiceException("Idempotency-Key 无效", HttpStatus.BAD_REQUEST.value());
        Binding binding = binding(accountId, applicationId, true);
        String issuerRef = logins.register(login);
        SessionDebugClient.CreatedSession created = sessionClient.create(accountId, applicationId, binding.configVersionId(), requestId);
        if (created.sessionId() <= 0 || created.applicationId() != applicationId || created.configVersionId() != binding.configVersionId())
            throw new ServiceException("会话服务返回无效 DEBUG Session", HttpStatus.BAD_GATEWAY.value());
        recordSessionReferences(accountId, created.sessionId(), binding, requestId);
        sessions.put(created.sessionId(), new Registration(accountId, applicationId, binding, issuerRef));
        return new DebugSession(created.sessionId(), applicationId, binding.configVersionId());
    }

    public SessionDebugClient.IssuedToken mint(long accountId, LoginUser login, long sessionId)
    {
        Registration registration = sessions.get(sessionId);
        if (registration == null || registration.accountId() != accountId || !logins.isCurrent(registration.issuerConsoleRef(), accountId))
            throw new ServiceException("DEBUG Session 已失效，请重新从当前登录创建", HttpStatus.UNAUTHORIZED.value());
        String currentRef = logins.register(login);
        if (!currentRef.equals(registration.issuerConsoleRef())) throw new ServiceException("当前登录与 DEBUG Session 不一致", HttpStatus.FORBIDDEN.value());
        Instant expiresAt = logins.expiry(registration.issuerConsoleRef());
        Integer allowed = jdbc.queryForObject("select count(1) from p_application where id=? and account_id=? and status='ACTIVE' and admin_disabled=0", Integer.class,
            registration.applicationId(), accountId);
        if (allowed == null || allowed != 1) throw new ServiceException("应用当前不可签发新授权", HttpStatus.FORBIDDEN.value());
        Binding binding = registration.binding();
        return sessionClient.mint(new SessionDebugClient.MintBody(accountId, registration.applicationId(), sessionId, binding.configVersionId(),
                registration.issuerConsoleRef(), expiresAt.toEpochMilli(), binding.voiceVersionId(), binding.providerKind(),
                binding.providerVoiceRef(), binding.relayVersionRef(), binding.officialServiceId(), binding.officialServiceRevision(),
                binding.mode()));
    }

    public void close(long accountId, LoginUser login, long sessionId)
    {
        Registration registration = sessions.get(sessionId);
        if (registration != null)
        {
            if (registration.accountId() != accountId || !logins.isCurrent(registration.issuerConsoleRef(), accountId))
                throw new ServiceException("DEBUG Session 已失效", HttpStatus.UNAUTHORIZED.value());
            String currentRef = logins.register(login);
            if (!currentRef.equals(registration.issuerConsoleRef())) throw new ServiceException("当前登录与 DEBUG Session 不一致", HttpStatus.FORBIDDEN.value());
        }
        // After a system restart the map is empty; the trusted session service still checks persistent account ownership.
        sessionClient.close(accountId, sessionId);
        if (registration != null) sessions.remove(sessionId, registration);
        jdbc.update("update p_resource_reference set state='RELEASED',released_at=utc_timestamp(3),updated_at=utc_timestamp(3) where holder_type='SESSION' and holder_id=? and account_id=? and state in ('RESERVED','CONFIRMED')", sessionId, accountId);
    }

    private Binding binding(long accountId, long applicationId, boolean allowVoicePreview)
    {
        Binding binding = jdbc.query("select c.id,c.voice_version_id,v.service_type,v.voice_code,v.relay_version_id,c.avatar_version_id,official.id,official.revision,c.mode from p_application a join p_app_config c on c.id = a.current_config_id join p_voice_version v on v.id = c.voice_version_id join p_voice voice on voice.id = v.voice_id left join p_official_service official on official.id = v.official_service_id where a.id = ? and a.account_id = ? and a.status = 'ACTIVE' and (a.purpose != 'USER' or a.admin_disabled = 0) and ((a.purpose = 'USER' and c.mode in ('CHAT','SPEAK_ONLY') and voice.status in ('PUBLISHED','UNLISTED') and voice.visibility = 'OFFICIAL' and v.service_type = 'OFFICIAL') or (? = 1 and a.purpose = 'VOICE_PREVIEW' and c.mode = 'SPEAK_ONLY' and a.preview_voice_version_id = v.id and voice.visibility = 'OFFICIAL')) and (v.service_type = 'RELAY' or (official.capability = 'TTS' and official.status = 'ACTIVE'))",
            rs -> rs.next() ? new Binding(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getObject(5) == null ? null : rs.getString(5), rs.getLong(6), rs.getObject(7) == null ? null : rs.getLong(7), rs.getObject(8) == null ? null : rs.getLong(8), rs.getString(9)) : null,
                applicationId, accountId, allowVoicePreview ? 1 : 0);
        if (binding == null) throw new ServiceException("应用没有可用的已发布 Voice 配置", HttpStatus.CONFLICT.value());
        Integer actionCount = jdbc.queryForObject("select count(1) from p_avatar_version v join p_avatar a on a.id = v.avatar_id join p_avatar_action action on action.avatar_version_id = v.id where v.id = ? and v.status = 'PUBLISHED' and a.status in ('PUBLISHED','UNLISTED') and (v.account_id = ? or a.visibility = 'OFFICIAL') and action.action_code in ('idle','speaking','listening','thinking','nod','shake_head','wave','happy')",
                Integer.class, binding.avatarVersionId(), accountId);
        if (actionCount == null || actionCount != 8) throw new ServiceException("应用 Avatar 尚未发布完整八动作版本", HttpStatus.CONFLICT.value());
        return binding;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private void recordSessionReferences(long accountId, long sessionId, Binding binding, String operationId)
    {
        Instant now = Instant.now();
        insertSessionReference(accountId, sessionId, operationId, "APP_CONFIG", binding.configVersionId(), now);
        insertSessionReference(accountId, sessionId, operationId, "AVATAR_VERSION", binding.avatarVersionId(), now);
        insertSessionReference(accountId, sessionId, operationId, "VOICE_VERSION", binding.voiceVersionId(), now);
        if ("CHAT".equals(binding.mode()))
        {
            Long relayId = jdbc.queryForObject("select llm_relay_version_id from p_app_config where id=? and account_id=?",
                Long.class, binding.configVersionId(), accountId);
            if (relayId == null) throw new ServiceException("CHAT Relay 配置不可用", HttpStatus.CONFLICT.value());
            insertSessionReference(accountId, sessionId, operationId, "RELAY_VERSION", relayId, now);
            java.util.List<Long> skills = jdbc.query("select skill_version_id from p_app_skill where app_config_id=? and account_id=? and enabled=1",
                (rs, index) -> rs.getLong(1), binding.configVersionId(), accountId);
            for (Long skillId : skills)
                insertSessionReference(accountId, sessionId, operationId, "SKILL_VERSION", skillId, now);
        }
    }
    private void insertSessionReference(long accountId, long sessionId, String operationId, String type, long resourceId, Instant now)
    {
        jdbc.update("insert ignore into p_resource_reference (id,created_at,updated_at,account_id,holder_type,holder_id,operation_id,resource_type,resource_id,state,confirmed_at) values (uuid_short(),?,?,?,'SESSION',?,?,?,?,'CONFIRMED',?)",
            now, now, accountId, sessionId, "debug:" + operationId, type, resourceId, now);
    }
    private record Registration(long accountId, long applicationId, Binding binding, String issuerConsoleRef) { }
    private record Binding(long configVersionId, long voiceVersionId, String providerKind, String providerVoiceRef,
            String relayVersionRef, long avatarVersionId, Long officialServiceId, Long officialServiceRevision,
            String mode) { }
    public record DebugSession(long sessionId, long applicationId, long configVersionId) { }
}
