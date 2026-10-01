package com.ruoyi.system.skill.controller;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.skill.service.ISkillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Developer-only private Skill control plane. Official Skills are read-only candidates here. */
@RestController
@RequestMapping("/api/v1/developer/skills")
public class SkillController
{
    private final ISkillService skills;
    public SkillController(ISkillService skills) { this.skills = skills; }
    @RequiresPermissions("platform:skill:read")
    @GetMapping public AjaxResult list(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(skills.list(developer(), pageNum, pageSize)); }
    @RequiresPermissions("platform:skill:read")
    @GetMapping("/candidates") public AjaxResult candidates()
    { return AjaxResult.success(skills.candidates(developer())); }
    @RequiresPermissions("platform:skill:read")
    @GetMapping("/{skillId}") public AjaxResult detail(@PathVariable long skillId)
    { return AjaxResult.success(skills.detail(developer(), skillId)); }
    @RequiresPermissions("platform:skill:write")
    @PostMapping public AjaxResult create(@Valid @RequestBody ISkillService.SkillInput input,
        @RequestHeader("Idempotency-Key") String key)
    { long account = developer(); return AjaxResult.success(skills.create(account, account, input, key, false)); }
    @RequiresPermissions("platform:skill:write")
    @PutMapping("/{skillId}") public AjaxResult update(@PathVariable long skillId,
        @Valid @RequestBody ISkillService.SkillInput input, @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String key)
    { long account = developer(); return AjaxResult.success(skills.update(account, account, skillId, input, ifMatch, key, false)); }
    @RequiresPermissions("platform:skill:write")
    @PostMapping("/{skillId}/status") public AjaxResult status(@PathVariable long skillId,
        @Valid @RequestBody ISkillService.StatusInput input, @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String key)
    { return AjaxResult.success(skills.changeStatus(developer(), skillId, input, ifMatch, key, false)); }
    @RequiresPermissions("platform:skill:write")
    @PostMapping("/{skillId}/connection-check") public AjaxResult check(@PathVariable long skillId)
    { return AjaxResult.success(skills.checkConnection(developer(), skillId, false)); }
    @RequiresPermissions("platform:skill:write")
    @DeleteMapping("/{skillId}") public AjaxResult delete(@PathVariable long skillId,
        @RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String key)
    { return AjaxResult.success(skills.delete(developer(), skillId, ifMatch, key, false)); }

    private static long developer()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getToken() == null || login.getUserid() == null || login.getUserid() <= 0)
            throw new ServiceException("后台登录无效", 401);
        if (login.getRoles() == null || !login.getRoles().contains("developer") || login.getRoles().contains("admin"))
            throw new ServiceException("仅开发者账号可访问", 403);
        return login.getUserid();
    }
}
