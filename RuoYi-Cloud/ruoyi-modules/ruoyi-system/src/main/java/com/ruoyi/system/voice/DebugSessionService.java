package com.ruoyi.system.voice;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.api.model.LoginUser;

/** Derives DEBUG identity only from the current C login and freezes a published SPEAK_ONLY configuration. */
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
        Binding binding = registration.binding();
        return sessionClient.mint(new SessionDebugClient.MintBody(accountId, registration.applicationId(), sessionId, binding.configVersionId(),
                registration.issuerConsoleRef(), expiresAt.toEpochMilli(), binding.voiceVersionId(), binding.providerKind(),
                binding.providerVoiceRef(), binding.relayVersionRef()));
    }

    public void close(long accountId, LoginUser login, long sessionId)
    {
        Registration registration = sessions.remove(sessionId);
        if (registration != null)
        {
            if (registration.accountId() != accountId || !logins.isCurrent(registration.issuerConsoleRef(), accountId))
                throw new ServiceException("DEBUG Session 已失效", HttpStatus.UNAUTHORIZED.value());
            String currentRef = logins.register(login);
            if (!currentRef.equals(registration.issuerConsoleRef())) throw new ServiceException("当前登录与 DEBUG Session 不一致", HttpStatus.FORBIDDEN.value());
        }
        // After a system restart the map is empty; the trusted session service still checks persistent account ownership.
        sessionClient.close(accountId, sessionId);
    }

    private Binding binding(long accountId, long applicationId, boolean allowVoicePreview)
    {
        Binding binding = jdbc.query("select c.id,c.voice_version_id,v.service_type,v.voice_code,v.relay_version_id,c.avatar_version_id from p_application a join p_app_config c on c.id = a.current_config_id join p_voice_version v on v.id = c.voice_version_id join p_voice voice on voice.id = v.voice_id left join p_official_service official on official.id = v.official_service_id where a.id = ? and a.account_id = ? and a.status = 'ACTIVE' and c.mode = 'SPEAK_ONLY' and ((a.purpose = 'USER' and voice.status = 'PUBLISHED' and (voice.account_id = a.account_id or voice.visibility = 'OFFICIAL')) or (? = 1 and a.purpose = 'VOICE_PREVIEW' and a.preview_voice_version_id = v.id and voice.visibility = 'OFFICIAL')) and (v.service_type = 'RELAY' or (official.capability = 'TTS' and official.status = 'ACTIVE'))",
            rs -> rs.next() ? new Binding(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getObject(5) == null ? null : rs.getString(5), rs.getLong(6)) : null,
                applicationId, accountId, allowVoicePreview ? 1 : 0);
        if (binding == null) throw new ServiceException("应用没有可用的 SPEAK_ONLY 已发布 Voice 配置", HttpStatus.CONFLICT.value());
        Integer actionCount = jdbc.queryForObject("select count(1) from p_avatar_version v join p_avatar a on a.id = v.avatar_id join p_avatar_action action on action.avatar_version_id = v.id where v.id = ? and v.status = 'PUBLISHED' and a.status in ('PUBLISHED','UNLISTED') and (v.account_id = ? or a.visibility = 'OFFICIAL') and action.action_code in ('idle','speaking','listening','thinking','nod','shake_head','wave','happy')",
                Integer.class, binding.avatarVersionId(), accountId);
        if (actionCount == null || actionCount != 8) throw new ServiceException("应用 Avatar 尚未发布完整八动作版本", HttpStatus.CONFLICT.value());
        return binding;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record Registration(long accountId, long applicationId, Binding binding, String issuerConsoleRef) { }
    private record Binding(long configVersionId, long voiceVersionId, String providerKind, String providerVoiceRef,
            String relayVersionRef, long avatarVersionId) { }
    public record DebugSession(long sessionId, long applicationId, long configVersionId) { }
}
