package com.ruoyi.system.voice;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.constant.TokenConstants;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;

/** C-console endpoints. They derive identity from the authenticated request and never accept user/principal fields. */
@RestController
@RequestMapping("/api/v1")
public class DebugSessionController
{
    private final DebugSessionService sessions;
    public DebugSessionController(DebugSessionService sessions) { this.sessions = sessions; }

    @RequiresPermissions("platform:application:debug")
    @PostMapping("/applications/{applicationId}/debug-sessions")
    public DebugSessionService.DebugSession create(@PathVariable long applicationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    {
        LoginUser login = requireLogin();
        return sessions.create(login.getUserid(), login, applicationId, idempotencyKey);
    }

    @PostMapping("/debug-sessions/{sessionId}/tokens")
    public SessionDebugClient.IssuedToken mint(@PathVariable long sessionId)
    {
        LoginUser login = requireLogin();
        return sessions.mint(login.getUserid(), login, sessionId);
    }

    @DeleteMapping("/debug-sessions/{sessionId}")
    public void close(@PathVariable long sessionId)
    {
        LoginUser login = requireLogin();
        sessions.close(login.getUserid(), login, sessionId);
    }

    private static LoginUser requireLogin()
    {
        LoginUser login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null || login.getToken() == null) throw new com.ruoyi.common.core.exception.ServiceException("当前后台登录无效", 401);
        return login;
    }
}
