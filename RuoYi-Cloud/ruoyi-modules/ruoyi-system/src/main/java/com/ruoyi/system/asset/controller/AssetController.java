package com.ruoyi.system.asset.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.asset.dto.AvatarStatusReasonRequest;
import com.ruoyi.system.asset.dto.CreateAvatarVersionRequest;
import com.ruoyi.system.asset.dto.CreateGenerationTaskRequest;
import com.ruoyi.system.asset.dto.PublishAvatarVersionRequest;
import com.ruoyi.system.asset.service.IAssetService;
import com.ruoyi.system.asset.service.IAvatarPublicationService;
import com.ruoyi.system.asset.service.IAvatarProductionService;

/** 已登录账号的私有参考图与 Avatar 制作任务 API。 */
@RestController
@RequestMapping("/asset")
public class AssetController
{
    private final IAssetService assetService;
    private final IAvatarPublicationService publicationService;
    private final IAvatarProductionService productionService;

    public AssetController(IAssetService assetService, IAvatarPublicationService publicationService,
        IAvatarProductionService productionService)
    {
        this.assetService = assetService;
        this.publicationService = publicationService;
        this.productionService = productionService;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void consoleIdentity(@PathVariable(required = false) Long avatarId)
    {
        AssetConsoleIdentity.console();
        if (avatarId != null) AssetConsoleIdentity.avatar(publicationService, avatarId);
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
    public AjaxResult createGenerationTask(@Valid @RequestBody CreateGenerationTaskRequest request)
    {
        return AjaxResult.success(assetService.createGenerationTask(SecurityUtils.getUserId(), request));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/generation-tasks")
    public AjaxResult listGenerationTasks(@RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize)
    {
        if (pageNum != null || pageSize != null)
            return AjaxResult.success(assetService.pageConsoleGenerationTasks(SecurityUtils.getUserId(),
                pageNum == null ? 1 : pageNum, pageSize == null ? 20 : pageSize));
        return AjaxResult.success(assetService.listGenerationTasks(SecurityUtils.getUserId()));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/public-avatars")
    public AjaxResult listPublicAvatars(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    {
        return AjaxResult.success(publicationService.listPublic(pageNum, pageSize));
    }

    /** Personal catalogue: independent from the recent generation-task list. */
    @RequiresPermissions("system:asset:list")
    @GetMapping("/avatars")
    public AjaxResult listOwnedAvatars(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status,
        @RequestParam(required = false) String keyword)
    {
        AssetConsoleIdentity.developer();
        return AjaxResult.success(publicationService.listOwned(SecurityUtils.getUserId(), pageNum, pageSize, status, keyword));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/admin/public-avatars")
    public AjaxResult listAdminPublicAvatars(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status)
    {
        AssetConsoleIdentity.administrator();
        return AjaxResult.success(publicationService.listAdminPublic(pageNum, pageSize, status));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/generation-services")
    public AjaxResult listAvatarGenerationServices()
    {
        return AjaxResult.success(assetService.listAvatarGenerationServices(SecurityUtils.getUserId()));
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

    @RequiresPermissions("system:asset:list")
    @GetMapping("/avatars/{avatarId}")
    public AjaxResult avatarDetail(@PathVariable Long avatarId)
    {
        return AjaxResult.success(publicationService.detail(SecurityUtils.getUserId(), avatarId));
    }

    @RequiresPermissions("system:asset:list")
    @GetMapping("/avatars/{avatarId}/references")
    public AjaxResult avatarReferences(@PathVariable Long avatarId)
    {
        return AjaxResult.success(publicationService.references(SecurityUtils.getUserId(), avatarId));
    }

    @Log(title = "官方 Avatar 下架", businessType = BusinessType.UPDATE)
    @RequiresPermissions("system:asset:edit")
    @PostMapping("/admin/avatars/{avatarId}/unpublish")
    public AjaxResult unpublishAvatar(@PathVariable Long avatarId,
        @Valid @RequestBody AvatarStatusReasonRequest request)
    {
        return AjaxResult.success(publicationService.unpublish(SecurityUtils.getUserId(), avatarId, request));
    }

    @Log(title = "官方 Avatar 紧急停用", businessType = BusinessType.UPDATE)
    @RequiresPermissions("system:asset:edit")
    @PostMapping("/admin/avatars/{avatarId}/disable")
    public AjaxResult disableAvatar(@PathVariable Long avatarId,
        @Valid @RequestBody AvatarStatusReasonRequest request)
    {
        return AjaxResult.success(publicationService.disable(SecurityUtils.getUserId(), avatarId, request));
    }

    @Log(title = "Avatar 新候选版本", businessType = BusinessType.INSERT)
    @RequiresPermissions("system:asset:add")
    @PostMapping("/avatars/{avatarId}/versions")
    public AjaxResult createAvatarVersion(@PathVariable Long avatarId,
        @Valid @RequestBody CreateAvatarVersionRequest request)
    {
        return AjaxResult.success(productionService.createVersion(SecurityUtils.getUserId(), avatarId, request));
    }

    @Log(title = "Avatar 版本发布", businessType = BusinessType.UPDATE)
    @RequiresPermissions("system:asset:edit")
    @PostMapping("/avatars/{avatarId}/versions/{versionId}/publish")
    public AjaxResult publishAvatarVersion(@PathVariable Long avatarId, @PathVariable Long versionId,
        @Valid @RequestBody PublishAvatarVersionRequest request)
    {
        return AjaxResult.success(publicationService.publish(SecurityUtils.getUserId(), avatarId, versionId, request));
    }

    @Log(title = "Avatar 删除", businessType = BusinessType.DELETE)
    @RequiresPermissions("system:asset:remove")
    @DeleteMapping("/avatars/{avatarId}")
    public ResponseEntity<AjaxResult> deleteAvatar(@PathVariable Long avatarId,
        @org.springframework.web.bind.annotation.RequestHeader(value = "If-Match", required = false) String ifMatch,
        @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    {
        return ResponseEntity.accepted().body(AjaxResult.success(publicationService.deleteAvatar(
            SecurityUtils.getUserId(), avatarId, ifMatch, idempotencyKey)));
    }
}
