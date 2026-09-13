package com.ruoyi.system.asset.controller;

import org.springframework.web.bind.annotation.GetMapping;
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
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.asset.dto.CreateGenerationTaskRequest;
import com.ruoyi.system.asset.service.AssetService;

/** 已登录账号的私有参考图与 Avatar 制作任务 API。 */
@RestController
@RequestMapping("/asset")
public class AssetController
{
    private final AssetService assetService;

    public AssetController(AssetService assetService)
    {
        this.assetService = assetService;
    }

    @Log(title = "参考图上传", businessType = BusinessType.INSERT)
    @PostMapping("/files")
    public AjaxResult uploadReference(@RequestParam("file") MultipartFile file,
        @RequestParam("rightsNoticeVersion") String rightsNoticeVersion,
        @RequestParam("rightsConfirmed") Boolean rightsConfirmed)
    {
        return AjaxResult.success(assetService.uploadReference(SecurityUtils.getUserId(), file, rightsNoticeVersion, rightsConfirmed));
    }

    @GetMapping("/files/{fileId}")
    public AjaxResult readReference(@PathVariable Long fileId)
    {
        return AjaxResult.success(assetService.readReference(SecurityUtils.getUserId(), fileId));
    }

    @Log(title = "Avatar 制作任务", businessType = BusinessType.INSERT)
    @PostMapping("/generation-tasks")
    public AjaxResult createGenerationTask(@RequestBody CreateGenerationTaskRequest request)
    {
        return AjaxResult.success(assetService.createGenerationTask(SecurityUtils.getUserId(), request));
    }

    @GetMapping("/generation-tasks/{taskId}")
    public AjaxResult readGenerationTask(@PathVariable Long taskId)
    {
        return AjaxResult.success(assetService.readGenerationTask(SecurityUtils.getUserId(), taskId));
    }
}
