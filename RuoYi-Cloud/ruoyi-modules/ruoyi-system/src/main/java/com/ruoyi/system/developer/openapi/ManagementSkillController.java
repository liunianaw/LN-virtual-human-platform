package com.ruoyi.system.developer.openapi;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.skill.service.ISkillService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/openapi/v1/management/skills")
public class ManagementSkillController
{
    private final ISkillService skills;
    public ManagementSkillController(ISkillService skills) { this.skills = skills; }
    @GetMapping public AjaxResult list(HttpServletRequest request,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(skills.list(account(request), pageNum, pageSize)); }
    @GetMapping("/candidates") public AjaxResult candidates(HttpServletRequest request)
    { return AjaxResult.success(skills.candidates(account(request))); }
    @GetMapping("/{skillId}") public AjaxResult detail(HttpServletRequest request, @PathVariable long skillId)
    { return AjaxResult.success(skills.detail(account(request), skillId)); }
    @PostMapping public AjaxResult create(HttpServletRequest request, @Valid @RequestBody ISkillService.CreateInput input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(request); return AjaxResult.success(skills.create(accountId, accountId, input, key, false)); }
    @PostMapping("/{skillId}/versions") public AjaxResult version(HttpServletRequest request, @PathVariable long skillId,
        @Valid @RequestBody ISkillService.VersionInput input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(request); return AjaxResult.success(skills.addVersion(accountId, accountId, skillId, input, ifMatch, key)); }
    @PostMapping("/{skillId}/status") public AjaxResult status(HttpServletRequest request, @PathVariable long skillId,
        @Valid @RequestBody ISkillService.StatusInput input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(skills.changeStatus(account(request), skillId, input, ifMatch, key)); }
    @PostMapping("/{skillId}/connection-check") public AjaxResult check(HttpServletRequest request, @PathVariable long skillId)
    { return AjaxResult.success(skills.checkConnection(account(request), skillId)); }
    @DeleteMapping("/{skillId}") public AjaxResult delete(HttpServletRequest request, @PathVariable long skillId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(skills.delete(account(request), skillId, ifMatch, key)); }
    private static long account(HttpServletRequest request)
    {
        Object principal = request.getAttribute(ManagementKeyFilter.PRINCIPAL);
        if (principal instanceof IAccessKeyService.Principal key && "MANAGEMENT".equals(key.type()) && key.accountId() > 0)
            return key.accountId();
        throw new ServiceException("管理 Key 身份无效", 401);
    }
}
