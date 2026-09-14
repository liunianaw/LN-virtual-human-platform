package com.ruoyi.system.asset.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.asset.dto.CreateGenerationTaskRequest;
import com.ruoyi.system.asset.dto.PublishAvatarVersionRequest;
import com.ruoyi.system.asset.service.AssetService;
import com.ruoyi.system.asset.service.AvatarPublicationService;

/** 已登录账号的私有参考图与 Avatar 制作任务 API。 */
@RestController
@RequestMapping("/asset")
public class AssetController
{
    private final AssetService assetService;
    private final AvatarPublicationService publicationService;

    public AssetController(AssetService assetService, AvatarPublicationService publicationService)
    {
        this.assetService = assetService;
        this.publicationService = publicationService;
    }

    @Log(title = "参考图上传", businessType = BusinessType.INSERT)
    @RequiresPermissions("system:asset:add")
    @PostMapping("/files")
    public AjaxResult uploadReference(@RequestParam("file") MultipartFile file,
        @RequestParam("rightsNoticeVersion") String rightsNoticeVersion,
        @RequestParam("rightsConfirmed") Boolean rightsConfirmed)
    {
        return AjaxResult.success(assetService.uploadReference(SecurityUtils.getUserId(), file, rightsNoticeVersion, rightsConfirmed));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/files/{fileId}")
    public AjaxResult readReference(@PathVariable Long fileId)
    {
        return AjaxResult.success(assetService.readReference(SecurityUtils.getUserId(), fileId));
    }

    @Log(title = "Avatar 制作任务", businessType = BusinessType.INSERT)
    @RequiresPermissions("system:asset:add")
    @PostMapping("/generation-tasks")
    public AjaxResult createGenerationTask(@RequestBody CreateGenerationTaskRequest request)
    {
        return AjaxResult.success(assetService.createGenerationTask(SecurityUtils.getUserId(), request));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/generation-tasks")
    public AjaxResult listGenerationTasks()
    {
        return AjaxResult.success(assetService.listGenerationTasks(SecurityUtils.getUserId()));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/generation-tasks/{taskId}")
    public AjaxResult readGenerationTask(@PathVariable Long taskId)
    {
        return AjaxResult.success(assetService.readGenerationTask(SecurityUtils.getUserId(), taskId));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/avatars/{avatarId}/versions/{versionId}/preview")
    public AjaxResult previewAvatarVersion(@PathVariable Long avatarId, @PathVariable Long versionId)
    {
        return AjaxResult.success(publicationService.preview(SecurityUtils.getUserId(), avatarId, versionId));
    }

    @Log(title = "Avatar 版本发布", businessType = BusinessType.UPDATE)
    @RequiresPermissions("system:asset:edit")
    @PostMapping("/avatars/{avatarId}/versions/{versionId}/publish")
    public AjaxResult publishAvatarVersion(@PathVariable Long avatarId, @PathVariable Long versionId,
        @RequestBody PublishAvatarVersionRequest request)
    {
        return AjaxResult.success(publicationService.publish(SecurityUtils.getUserId(), avatarId, versionId, request));
    }

    @Log(title = "Avatar 删除", businessType = BusinessType.DELETE)
    @RequiresPermissions("system:asset:remove")
    @DeleteMapping("/avatars/{avatarId}")
    public AjaxResult deleteAvatar(@PathVariable Long avatarId)
    {
        publicationService.deleteAvatar(SecurityUtils.getUserId(), avatarId);
        return AjaxResult.success();
    }
}
