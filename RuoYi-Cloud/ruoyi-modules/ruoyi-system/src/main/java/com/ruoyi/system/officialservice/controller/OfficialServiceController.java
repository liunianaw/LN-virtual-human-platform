package com.ruoyi.system.officialservice.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.system.officialservice.dto.OfficialServiceCredentialRequest;
import com.ruoyi.system.officialservice.dto.OfficialServiceInput;
import com.ruoyi.system.officialservice.dto.OfficialServiceStatusRequest;
import com.ruoyi.system.officialservice.service.IOfficialServiceService;

@RestController
@RequestMapping("/api/v1/admin/official-services")
public class OfficialServiceController
{
    private final IOfficialServiceService services;
    public OfficialServiceController(IOfficialServiceService services) { this.services = services; }
    @RequiresPermissions("platform:service:read") @GetMapping public AjaxResult list(@RequestParam(required = false) String capability, @RequestParam(required = false) String status, @RequestParam(required = false) Integer pageNum, @RequestParam(required = false) Integer pageSize) { administrator(); return AjaxResult.success(services.list(capability, status, pageNum, pageSize)); }
    @RequiresPermissions("platform:service:read") @GetMapping("/{serviceId}") public AjaxResult read(@PathVariable long serviceId) { administrator(); return AjaxResult.success(services.read(serviceId)); }
    @Log(title = "官方服务保存", businessType = BusinessType.INSERT) @RequiresPermissions("platform:service:write") @PostMapping public AjaxResult create(@Valid @RequestBody OfficialServiceInput input, @RequestHeader(value = "Idempotency-Key", required = false) String key) { return AjaxResult.success(services.create(administrator().getUserid(), input, key)); }
    @Log(title = "官方服务修改", businessType = BusinessType.UPDATE) @RequiresPermissions("platform:service:write") @PutMapping("/{serviceId}") public AjaxResult update(@PathVariable long serviceId, @Valid @RequestBody OfficialServiceInput input, @RequestHeader(value = "If-Match", required = false) String match, @RequestHeader(value = "Idempotency-Key", required = false) String key) { return AjaxResult.success(services.update(administrator().getUserid(), serviceId, input, match, key)); }
    @Log(title = "官方服务凭证替换", businessType = BusinessType.UPDATE, isSaveRequestData = false) @RequiresPermissions("platform:service:write") @PutMapping("/{serviceId}/credential") public AjaxResult credential(@PathVariable long serviceId, @Valid @RequestBody OfficialServiceCredentialRequest input, @RequestHeader(value = "If-Match", required = false) String match, @RequestHeader(value = "Idempotency-Key", required = false) String key) { return AjaxResult.success(services.replaceCredential(administrator().getUserid(), serviceId, input, match, key)); }
    @Log(title = "官方服务检查", businessType = BusinessType.OTHER, isSaveRequestData = false) @RequiresPermissions("platform:service:check") @PostMapping("/{serviceId}/checks") public AjaxResult check(@PathVariable long serviceId, @RequestHeader(value = "If-Match", required = false) String match, @RequestHeader(value = "Idempotency-Key", required = false) String key) { administrator(); return AjaxResult.success(services.check(serviceId, match, key)); }
    @Log(title = "官方服务启停", businessType = BusinessType.UPDATE) @RequiresPermissions("platform:service:write") @PostMapping("/{serviceId}/status") public AjaxResult status(@PathVariable long serviceId, @Valid @RequestBody OfficialServiceStatusRequest input, @RequestHeader(value = "If-Match", required = false) String match, @RequestHeader(value = "Idempotency-Key", required = false) String key) { administrator(); return AjaxResult.success(services.changeStatus(serviceId, input, match, key)); }
    private static LoginUser administrator() { LoginUser login = SecurityUtils.getLoginUser(); if (login == null || login.getUserid() == null || !SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可管理官方服务", 403); return login; }
}
