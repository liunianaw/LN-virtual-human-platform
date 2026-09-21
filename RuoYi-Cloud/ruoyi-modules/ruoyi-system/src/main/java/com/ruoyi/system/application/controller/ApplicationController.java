package com.ruoyi.system.application.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.system.application.dto.ApplicationConfigRequest;
import com.ruoyi.system.application.dto.ApplicationStatusRequest;
import com.ruoyi.system.application.dto.CreateApplicationRequest;
import com.ruoyi.system.application.service.IApplicationService;

/** Console-only application management. Identity is always derived from the logged-in C principal. */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController
{
    private final IApplicationService applications;
    public ApplicationController(IApplicationService applications) { this.applications = applications; }

    @RequiresPermissions("platform:application:read")
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize,
        @RequestParam(required = false) String status)
    { return AjaxResult.success(applications.list(login().getUserid(), pageNum, pageSize, status)); }

    @RequiresPermissions("platform:application:read")
    @GetMapping("/resources")
    public AjaxResult choices() { return AjaxResult.success(applications.choices(login().getUserid())); }

    @RequiresPermissions("platform:application:read")
    @GetMapping("/{applicationId}")
    public AjaxResult detail(@PathVariable long applicationId)
    { return AjaxResult.success(applications.detail(login().getUserid(), applicationId)); }

    @RequiresPermissions("platform:application:read")
    @GetMapping("/{applicationId}/config-versions/{configVersionId}")
    public AjaxResult config(@PathVariable long applicationId, @PathVariable long configVersionId)
    { return AjaxResult.success(applications.config(login().getUserid(), applicationId, configVersionId)); }

    @RequiresPermissions("platform:application:write")
    @PostMapping
    public AjaxResult create(@Valid @RequestBody CreateApplicationRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(applications.create(login().getUserid(), request, key)); }

    @RequiresPermissions("platform:application:write")
    @PostMapping("/{applicationId}/config-versions")
    public AjaxResult publish(@PathVariable long applicationId, @Valid @RequestBody ApplicationConfigRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(applications.publish(login().getUserid(), applicationId, request, ifMatch, key)); }

    @RequiresPermissions("platform:application:write")
    @PostMapping("/{applicationId}/status")
    public AjaxResult status(@PathVariable long applicationId, @Valid @RequestBody ApplicationStatusRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(applications.changeStatus(login().getUserid(), applicationId, request, ifMatch, key)); }

    private static LoginUser login()
    {
        LoginUser login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null || login.getUserid() <= 0 || login.getToken() == null)
            throw new ServiceException("当前后台登录无效", 401);
        return login;
    }
}
