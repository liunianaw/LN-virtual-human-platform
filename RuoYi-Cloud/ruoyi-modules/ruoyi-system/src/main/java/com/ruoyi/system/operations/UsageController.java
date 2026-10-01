package com.ruoyi.system.operations;

import java.time.LocalDate;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.operations.service.IUsageService;
import com.ruoyi.system.operations.dto.AccountLimitsRequest;
import com.ruoyi.system.operations.dto.QuotaGrantRequest;

@RestController
public class UsageController
{
    private final IUsageService service;
    public UsageController(IUsageService service) { this.service = service; }
    @RequiresPermissions("platform:usage:read")
    @GetMapping("/api/v1/developer/usage")
    public AjaxResult usage(@RequestParam(required = false) String applicationId,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false) String capability, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.usage(developer(),applicationId,from,to,capability,pageNum,pageSize)); }
    @RequiresPermissions("platform:usage:read")
    @GetMapping("/api/v1/developer/call-records")
    public AjaxResult calls(@RequestParam(required = false) String applicationId,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false) String capability, @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.calls(developer(),applicationId,from,to,capability,status,pageNum,pageSize)); }
    @RequiresPermissions("platform:usage:read")
    @GetMapping("/api/v1/developer/usage/limits")
    public AjaxResult limits() { return AjaxResult.success(service.limits(developer())); }
    @RequiresPermissions("platform:usage:read")
    @GetMapping("/api/v1/developer/usage/reservations")
    public AjaxResult reservations(@RequestParam(required = false) String state,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.reservations(developer(),state,pageNum,pageSize)); }

    @RequiresPermissions("platform:quota:manage")
    @GetMapping("/api/v1/admin/usage/overview")
    public AjaxResult adminOverview(@RequestParam(required = false) String from,
        @RequestParam(required = false) String to)
    { administrator(); return AjaxResult.success(service.adminOverview(from,to)); }
    @RequiresPermissions("platform:quota:manage")
    @GetMapping("/api/v1/admin/usage/accounts")
    public AjaxResult adminAccounts(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { administrator(); return AjaxResult.success(service.adminAccounts(keyword,from,to,pageNum,pageSize)); }
    @RequiresPermissions("platform:quota:manage")
    @GetMapping("/api/v1/admin/accounts/{accountId}/usage")
    public AjaxResult adminAccountUsage(@PathVariable long accountId,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to)
    { administrator(); return AjaxResult.success(service.adminAccountUsage(accountId,from,to)); }

    @RequiresPermissions("platform:operations:reconcile")
    @Log(title = "用量日汇总重建", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PostMapping("/api/v1/admin/usage/rebuild")
    public AjaxResult rebuild(@Valid @RequestBody Rebuild input)
    { administrator(); return AjaxResult.success(service.rebuild(input.accountId(), input.usageDate())); }
    @RequiresPermissions("platform:operations:reconcile")
    @Log(title = "额度预占核对", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PostMapping("/api/v1/admin/quota-reservations/{reservationId}/reviews")
    public AjaxResult review(@PathVariable long reservationId, @Valid @RequestBody Review input)
    { administrator(); return AjaxResult.success(service.reviewReservation(SecurityUtils.getUserId(),reservationId,
        input.decision(),input.evidenceNote())); }
    @RequiresPermissions("platform:quota:manage")
    @GetMapping("/api/v1/admin/accounts/{accountId}/quotas")
    public AjaxResult accountQuotas(@PathVariable long accountId)
    { administrator(); return AjaxResult.success(service.adminLimits(accountId)); }
    @RequiresPermissions("platform:quota:manage")
    @Log(title = "账号限额配置", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PutMapping("/api/v1/admin/accounts/{accountId}/limits")
    public AjaxResult configureLimits(@PathVariable long accountId, @Valid @RequestBody AccountLimitsRequest input)
    { administrator(); return AjaxResult.success(service.configureLimits(accountId, input)); }
    @RequiresPermissions("platform:quota:manage")
    @Log(title = "账号额度授予", businessType = BusinessType.INSERT, isSaveRequestData = false)
    @PostMapping("/api/v1/admin/accounts/{accountId}/quota-grants")
    public AjaxResult grantQuota(@PathVariable long accountId, @Valid @RequestBody QuotaGrantRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { administrator(); return AjaxResult.success(service.grantQuota(SecurityUtils.getUserId(),accountId,input,key)); }
    private static void administrator()
    { if (!SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可核对用量与额度",403); }
    private static long developer()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null || login.getUserid() <= 0)
            throw new ServiceException("后台登录无效",401);
        if (login.getRoles() == null || !login.getRoles().contains("developer") || login.getRoles().contains("admin"))
            throw new ServiceException("仅开发者账号可查看自身用量",403);
        return login.getUserid();
    }
    public record Rebuild(@jakarta.validation.constraints.Positive long accountId,
            @jakarta.validation.constraints.NotNull LocalDate usageDate) { }
    public record Review(@jakarta.validation.constraints.NotBlank String decision,
            @jakarta.validation.constraints.NotBlank String evidenceNote) { }
}
