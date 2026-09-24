package com.ruoyi.system.developer.access.controller;

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
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.developer.access.dto.ApplicationSecretRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/secret")
public class ApplicationSecretController
{
    private final IAccessKeyService keys;
    public ApplicationSecretController(IAccessKeyService keys) { this.keys = keys; }

    @RequiresPermissions("platform:application:read")
    @GetMapping
    public AjaxResult list(@PathVariable long applicationId)
    { return AjaxResult.success(keys.list(account(), "APPLICATION", applicationId)); }

    @RequiresPermissions("platform:application:secret")
    @PostMapping("/reset")
    public AjaxResult reset(@PathVariable long applicationId, @Valid @RequestBody ApplicationSecretRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(keys.resetApplication(account(), applicationId, request.name(), key, null)); }

    @RequiresPermissions("platform:application:secret")
    @PostMapping("/{keyId}/disable")
    public AjaxResult disable(@PathVariable long applicationId, @PathVariable long keyId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(keys.changeApplicationStatus(account(), applicationId, keyId, "DISABLED", key, null)); }

    @RequiresPermissions("platform:application:secret")
    @PostMapping("/{keyId}/delete")
    public AjaxResult delete(@PathVariable long applicationId, @PathVariable long keyId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(keys.changeApplicationStatus(account(), applicationId, keyId, "DELETED", key, null)); }

    private static long account()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getToken() == null || login.getUserid() == null || login.getUserid() <= 0)
            throw new ServiceException("后台登录无效", 401);
        return login.getUserid();
    }
}
