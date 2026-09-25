package com.ruoyi.system.skill.controller;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.skill.service.ISkillService;
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
@RequestMapping("/api/v1/developer/skills")
public class SkillController
{
    private final ISkillService skills;
    public SkillController(ISkillService skills) { this.skills = skills; }
    @RequiresPermissions("platform:skill:read")
    @GetMapping public AjaxResult list(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(skills.list(account(), pageNum, pageSize)); }
    @RequiresPermissions("platform:skill:read")
    @GetMapping("/candidates") public AjaxResult candidates()
    { return AjaxResult.success(skills.candidates(account())); }
    @RequiresPermissions("platform:skill:read")
    @GetMapping("/{skillId}") public AjaxResult detail(@PathVariable long skillId)
    { return AjaxResult.success(skills.detail(account(), skillId)); }
    @RequiresPermissions("platform:skill:write")
    @PostMapping public AjaxResult create(@Valid @RequestBody ISkillService.CreateInput input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(); return AjaxResult.success(skills.create(accountId, accountId, input, key, false)); }
    @RequiresPermissions("platform:skill:official")
    @PostMapping("/official") public AjaxResult official(@Valid @RequestBody ISkillService.CreateInput input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(); return AjaxResult.success(skills.create(accountId, accountId, input, key, true)); }
    @RequiresPermissions("platform:skill:write")
    @PostMapping("/{skillId}/versions") public AjaxResult version(@PathVariable long skillId,
        @Valid @RequestBody ISkillService.VersionInput input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(); return AjaxResult.success(skills.addVersion(accountId, accountId, skillId, input, ifMatch, key)); }
    @RequiresPermissions("platform:skill:write")
    @PostMapping("/{skillId}/status") public AjaxResult status(@PathVariable long skillId,
        @Valid @RequestBody ISkillService.StatusInput input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(skills.changeStatus(account(), skillId, input, ifMatch, key)); }
    @RequiresPermissions("platform:skill:write")
    @PostMapping("/{skillId}/connection-check") public AjaxResult check(@PathVariable long skillId)
    { return AjaxResult.success(skills.checkConnection(account(), skillId)); }
    @RequiresPermissions("platform:skill:write")
    @DeleteMapping("/{skillId}") public AjaxResult delete(@PathVariable long skillId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(skills.delete(account(), skillId, ifMatch, key)); }
    private static long account()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getToken() == null || login.getUserid() == null || login.getUserid() <= 0)
            throw new ServiceException("后台登录无效", 401);
        return login.getUserid();
    }
}
