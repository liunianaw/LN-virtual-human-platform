package com.ruoyi.system.skill.controller;

import com.ruoyi.system.skill.service.ISkillService;
import com.ruoyi.system.voice.InternalBearerGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Current authorization is checked for each new Tool action. */
@RestController
@RequestMapping("/internal/v1/skills")
public class SkillInternalController
{
    private final InternalBearerGuard guard;
    private final ISkillService skills;
    public SkillInternalController(InternalBearerGuard guard, ISkillService skills)
    { this.guard = guard; this.skills = skills; }
    @PostMapping("/resolve")
    public ISkillService.ResolvedSkill resolve(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestBody ISkillService.RuntimeBinding binding)
    { guard.requireSession(authorization); return skills.resolve(binding); }
}
