package com.ruoyi.system.skill.controller;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.skill.service.ISkillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Administrator-only public Skill lifecycle. */
@RestController
@RequestMapping("/api/v1/admin/public-skills")
public class AdminPublicSkillController
{
    private final ISkillService skills;
    public AdminPublicSkillController(ISkillService skills) { this.skills = skills; }
    @RequiresPermissions("platform:skill:official")
    @GetMapping public AjaxResult list(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(skills.listOfficial(admin(), pageNum, pageSize)); }
    @RequiresPermissions("platform:skill:official")
    @GetMapping("/{skillId}") public AjaxResult detail(@PathVariable long skillId)
    { return AjaxResult.success(skills.detailOfficial(admin(), skillId)); }
    @RequiresPermissions("platform:skill:official")
    @PostMapping public AjaxResult create(@Valid @RequestBody ISkillService.SkillInput input,
        @RequestHeader("Idempotency-Key") String key)
    { long account = admin(); return AjaxResult.success(skills.create(account, account, input, key, true)); }
    @RequiresPermissions("platform:skill:official")
    @PutMapping("/{skillId}") public AjaxResult update(@PathVariable long skillId,
        @Valid @RequestBody ISkillService.SkillInput input, @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String key)
    { long account = admin(); return AjaxResult.success(skills.update(account, account, skillId, input, ifMatch, key, true)); }
    @RequiresPermissions("platform:skill:official")
    @PostMapping("/{skillId}/status") public AjaxResult status(@PathVariable long skillId,
        @Valid @RequestBody ISkillService.StatusInput input, @RequestHeader("If-Match") String ifMatch,
        @RequestHeader("Idempotency-Key") String key)
    { return AjaxResult.success(skills.changeStatus(admin(), skillId, input, ifMatch, key, true)); }
    @RequiresPermissions("platform:skill:official")
    @PostMapping("/{skillId}/connection-check") public AjaxResult check(@PathVariable long skillId)
    { return AjaxResult.success(skills.checkConnection(admin(), skillId, true)); }
    @RequiresPermissions("platform:skill:official")
    @DeleteMapping("/{skillId}") public AjaxResult delete(@PathVariable long skillId,
        @RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String key)
    { return AjaxResult.success(skills.delete(admin(), skillId, ifMatch, key, true)); }

    private static long admin()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getToken() == null || login.getUserid() == null || login.getUserid() <= 0)
            throw new ServiceException("后台登录无效", 401);
        if (login.getRoles() == null || !login.getRoles().contains("admin") || login.getRoles().contains("developer"))
            throw new ServiceException("仅管理员账号可访问", 403);
        return login.getUserid();
    }
}
