package com.ruoyi.system.application.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.application.dto.AdminApplicationRestrictionRequest;
import com.ruoyi.system.application.service.IApplicationService;

@RestController
@RequestMapping("/api/v1/admin/applications")
public class AdminApplicationRestrictionController
{
    private final IApplicationService applications;
    public AdminApplicationRestrictionController(IApplicationService applications) { this.applications = applications; }

    @RequiresPermissions("platform:application:admin-disable")
    @Log(title = "管理员应用限制", businessType = BusinessType.UPDATE, isSaveRequestData = true)
    @PostMapping("/{applicationId}/restriction")
    public AjaxResult change(@PathVariable long applicationId, @Valid @RequestBody AdminApplicationRestrictionRequest request)
    {
        if (!SecurityUtils.isAdmin() || SecurityUtils.getUserId() == null) throw new ServiceException("仅管理员可禁用应用", 403);
        return AjaxResult.success(applications.changeAdminDisabled(SecurityUtils.getUserId(), applicationId, request.disabled(), request.reason()));
    }
}
