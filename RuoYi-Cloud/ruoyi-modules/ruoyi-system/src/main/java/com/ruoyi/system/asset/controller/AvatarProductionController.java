package com.ruoyi.system.asset.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.asset.dto.AvatarActionGenerationRequest;
import com.ruoyi.system.asset.dto.AvatarActionSelectionRequest;
import com.ruoyi.system.asset.dto.AvatarAssemblyRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptDiscardRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptRequest;
import com.ruoyi.system.asset.service.IAvatarProductionService;

@RestController
@RequestMapping("/asset/avatars/{avatarId}/versions/{versionId}")
public class AvatarProductionController
{
    private final IAvatarProductionService productionService;

    public AvatarProductionController(IAvatarProductionService productionService)
    {
        this.productionService = productionService;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void consoleIdentity() { AssetConsoleIdentity.console(); }

    @GetMapping("/production")
    @RequiresPermissions("system:asset:list")
    public AjaxResult production(@PathVariable Long avatarId, @PathVariable Long versionId)
    {
        return AjaxResult.success(productionService.production(SecurityUtils.getUserId(), avatarId, versionId));
    }

    @PostMapping("/actions/{actionCode}/selection")
    @RequiresPermissions("system:asset:add")
    public AjaxResult selection(@PathVariable Long avatarId, @PathVariable Long versionId, @PathVariable String actionCode,
        @Valid @RequestBody AvatarActionSelectionRequest request)
    {
        return AjaxResult.success(productionService.select(SecurityUtils.getUserId(), avatarId, versionId, actionCode, request));
    }

    @PostMapping("/actions/{actionCode}/generations")
    @RequiresPermissions("system:asset:add")
    public AjaxResult generate(@PathVariable Long avatarId, @PathVariable Long versionId, @PathVariable String actionCode,
        @Valid @RequestBody AvatarActionGenerationRequest request)
    {
        return AjaxResult.success(productionService.generate(SecurityUtils.getUserId(), avatarId, versionId, actionCode, request));
    }

    @PostMapping("/actions/{actionCode}/attempts/{attemptId}/recovery")
    @RequiresPermissions("system:asset:add")
    public AjaxResult recover(@PathVariable Long avatarId, @PathVariable Long versionId, @PathVariable String actionCode,
        @PathVariable Long attemptId, @Valid @RequestBody AvatarAttemptRequest request)
    {
        return AjaxResult.success(productionService.recover(SecurityUtils.getUserId(), avatarId, versionId, actionCode,
            attemptId, request));
    }

    @PostMapping("/actions/{actionCode}/attempts/{attemptId}/discard")
    @RequiresPermissions("system:asset:add")
    public AjaxResult discard(@PathVariable Long avatarId, @PathVariable Long versionId, @PathVariable String actionCode,
        @PathVariable Long attemptId, @Valid @RequestBody AvatarAttemptDiscardRequest request)
    {
        return AjaxResult.success(productionService.discard(SecurityUtils.getUserId(), avatarId, versionId, actionCode,
            attemptId, request));
    }

    @PostMapping("/assemble")
    @RequiresPermissions("system:asset:add")
    public AjaxResult assemble(@PathVariable Long avatarId, @PathVariable Long versionId,
        @Valid @RequestBody AvatarAssemblyRequest request)
    {
        return AjaxResult.success(productionService.assemble(SecurityUtils.getUserId(), avatarId, versionId, request));
    }

    @GetMapping("/actions/{actionCode}/results/{resultId}/preview")
    @RequiresPermissions("system:asset:list")
    public AjaxResult preview(@PathVariable Long avatarId, @PathVariable Long versionId,
        @PathVariable String actionCode, @PathVariable Long resultId)
    {
        return AjaxResult.success(productionService.preview(SecurityUtils.getUserId(), avatarId, versionId, actionCode, resultId));
    }
}
