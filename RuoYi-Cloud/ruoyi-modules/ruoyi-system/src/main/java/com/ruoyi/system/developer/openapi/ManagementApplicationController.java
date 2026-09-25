package com.ruoyi.system.developer.openapi;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.application.dto.ApplicationConfigRequest;
import com.ruoyi.system.application.dto.ApplicationStatusRequest;
import com.ruoyi.system.application.dto.CreateApplicationRequest;
import com.ruoyi.system.application.service.IApplicationService;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1/management/applications")
public class ManagementApplicationController
{
    private final IApplicationService applications;
    public ManagementApplicationController(IApplicationService applications) { this.applications = applications; }

    @GetMapping public AjaxResult list(HttpServletRequest request,
        @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize,
        @RequestParam(required = false) String status)
    { return AjaxResult.success(applications.list(account(request), pageNum, pageSize, status)); }

    @GetMapping("/resources") public AjaxResult resources(HttpServletRequest request)
    { return AjaxResult.success(applications.choices(account(request))); }

    @GetMapping("/{applicationId}") public AjaxResult detail(HttpServletRequest request, @PathVariable long applicationId)
    { return AjaxResult.success(applications.detail(account(request), applicationId)); }

    @GetMapping("/{applicationId}/config-versions/{configVersionId}")
    public AjaxResult config(HttpServletRequest request, @PathVariable long applicationId, @PathVariable long configVersionId)
    { return AjaxResult.success(applications.config(account(request), applicationId, configVersionId)); }

    @PostMapping public AjaxResult create(HttpServletRequest request, @Valid @RequestBody CreateApplicationRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(applications.create(account(request), input, key)); }

    @PostMapping("/{applicationId}/config-versions")
    public AjaxResult publish(HttpServletRequest request, @PathVariable long applicationId,
        @Valid @RequestBody ApplicationConfigRequest input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(applications.publish(account(request), applicationId, input, ifMatch, key)); }

    @PostMapping("/{applicationId}/status")
    public AjaxResult status(HttpServletRequest request, @PathVariable long applicationId,
        @Valid @RequestBody ApplicationStatusRequest input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(applications.changeStatus(account(request), applicationId, input, ifMatch, key)); }

    private static long account(HttpServletRequest request)
    {
        Object principal = request.getAttribute(ManagementKeyFilter.PRINCIPAL);
        if (principal instanceof IAccessKeyService.Principal key && "MANAGEMENT".equals(key.type()) && key.accountId() > 0)
            return key.accountId();
        throw new ServiceException("管理 Key 身份无效", 401);
    }
}
