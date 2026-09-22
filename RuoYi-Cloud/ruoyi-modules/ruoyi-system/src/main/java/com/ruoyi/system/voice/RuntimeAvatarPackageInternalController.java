package com.ruoyi.system.voice;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.service.IAvatarPublicationService;

/** Session-only bridge for the frozen application configuration's formal Avatar package. */
@RestController
@RequestMapping("/internal/v1/runtime-avatar-packages")
public class RuntimeAvatarPackageInternalController
{
    private final InternalBearerGuard guard;
    private final JdbcTemplate jdbc;
    private final IAvatarPublicationService avatars;

    public RuntimeAvatarPackageInternalController(InternalBearerGuard guard, JdbcTemplate jdbc, IAvatarPublicationService avatars)
    { this.guard = guard; this.jdbc = jdbc; this.avatars = avatars; }

    @PostMapping
    public Map<String, Object> read(@RequestHeader("Authorization") String authorization, @RequestBody Request body)
    {
        guard.requireSession(authorization);
        if (body == null || body.accountId() <= 0 || body.configVersionId() <= 0) throw new ServiceException("运行时角色包请求无效", 400);
        Binding binding = jdbc.query("select c.avatar_version_id,v.avatar_id from p_app_config c join p_avatar_version v on v.id=c.avatar_version_id where c.id=? and c.account_id=? and c.mode='SPEAK_ONLY'",
            rs -> rs.next() ? new Binding(rs.getLong(1), rs.getLong(2)) : null, body.configVersionId(), body.accountId());
        if (binding == null) throw new ServiceException("运行时配置不存在", 404);
        return avatars.runtimePackage(body.accountId(), binding.avatarId(), binding.avatarVersionId());
    }

    private record Binding(long avatarVersionId, long avatarId) { }
    public record Request(long accountId, long configVersionId) { }
}
