package com.ruoyi.system.developer.openapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.operations.service.IUsageService;

@RestController
@RequestMapping("/openapi/v1/management")
public class ManagementUsageController
{
    private final IUsageService service;
    public ManagementUsageController(IUsageService service) { this.service = service; }
    @GetMapping("/usage") public AjaxResult usage(HttpServletRequest request,
        @RequestParam(required = false) String applicationId, @RequestParam(required = false) String from,
        @RequestParam(required = false) String to, @RequestParam(required = false) String capability,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.usage(account(request),applicationId,from,to,capability,pageNum,pageSize)); }
    @GetMapping("/call-records") public AjaxResult calls(HttpServletRequest request,
        @RequestParam(required = false) String applicationId, @RequestParam(required = false) String from,
        @RequestParam(required = false) String to, @RequestParam(required = false) String capability,
        @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.calls(account(request),applicationId,from,to,capability,status,pageNum,pageSize)); }
    @GetMapping("/usage/limits") public AjaxResult limits(HttpServletRequest request)
    { return AjaxResult.success(service.limits(account(request))); }
    @GetMapping("/usage/reservations") public AjaxResult reservations(HttpServletRequest request,
        @RequestParam(required = false) String state, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.reservations(account(request),state,pageNum,pageSize)); }
    private static long account(HttpServletRequest request)
    {
        Object principal = request.getAttribute(ManagementKeyFilter.PRINCIPAL);
        if (principal instanceof IAccessKeyService.Principal key && "MANAGEMENT".equals(key.type()) && key.accountId() > 0)
            return key.accountId();
        throw new ServiceException("管理 Key 身份无效",401);
    }
}
