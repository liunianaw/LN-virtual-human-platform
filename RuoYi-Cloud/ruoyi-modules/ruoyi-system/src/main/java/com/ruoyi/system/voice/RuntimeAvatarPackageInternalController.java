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

/** Session-only bridge for the frozen Session snapshot's formal Avatar version. */
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
        if (body == null || body.accountId() <= 0 || body.avatarVersionId() <= 0) throw new ServiceException("运行时角色包请求无效", 400);
        Binding binding = jdbc.query("select v.id,v.avatar_id from p_avatar_version v join p_avatar a on a.id=v.avatar_id " +
            "where v.id=? and v.status='PUBLISHED' and (a.visibility='OFFICIAL' or a.account_id=?)",
            rs -> rs.next() ? new Binding(rs.getLong(1), rs.getLong(2)) : null, body.avatarVersionId(), body.accountId());
        if (binding == null) throw new ServiceException("运行时配置不存在", 404);
        return avatars.runtimePackage(body.accountId(), binding.avatarId(), binding.avatarVersionId());
    }

    private record Binding(long avatarVersionId, long avatarId) { }
    public record Request(long accountId, long avatarVersionId) { }
}
