package com.ruoyi.system.developer.access.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.developer.access.dto.ManagementKeyRequest;
import com.ruoyi.system.developer.access.service.IAccessKeyService;

@RestController
@RequestMapping("/api/v1/developer/access-keys")
public class AccessKeyController
{
    private final IAccessKeyService keys;
    public AccessKeyController(IAccessKeyService keys) { this.keys = keys; }

    @RequiresPermissions("platform:access-key:read")
    @GetMapping
    public AjaxResult list() { return AjaxResult.success(keys.list(account(), "MANAGEMENT", null)); }

    @RequiresPermissions("platform:access-key:write")
    @PostMapping
    public AjaxResult create(@Valid @RequestBody ManagementKeyRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(keys.createManagement(account(), request.name(), request.scopes(), key, null)); }

    @RequiresPermissions("platform:access-key:write")
    @PostMapping("/{keyId}/rotations")
    public AjaxResult rotate(@PathVariable long keyId, @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(keys.rotateManagement(account(), keyId, key, null)); }

    @RequiresPermissions("platform:access-key:write")
    @PostMapping("/{keyId}/disable")
    public AjaxResult disable(@PathVariable long keyId, @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(keys.changeStatus(account(), keyId, "DISABLED", key, null)); }

    @RequiresPermissions("platform:access-key:write")
    @PostMapping("/{keyId}/delete")
    public AjaxResult delete(@PathVariable long keyId, @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(keys.changeStatus(account(), keyId, "DELETED", key, null)); }

    private static long account()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getToken() == null || login.getUserid() == null || login.getUserid() <= 0)
            throw new ServiceException("后台登录无效", 401);
        return login.getUserid();
    }
}
