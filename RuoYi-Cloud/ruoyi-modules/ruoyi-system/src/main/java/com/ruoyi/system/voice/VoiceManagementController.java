package com.ruoyi.system.voice;

import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/public-voices")
public class VoiceManagementController
{
    private final IVoiceManagementService service;
    public VoiceManagementController(IVoiceManagementService service) { this.service=service; }
    @RequiresPermissions("platform:officialVoice:read") @GetMapping("/capabilities")
    public AjaxResult capabilities() { return AjaxResult.success(service.capabilities()); }
    @RequiresPermissions("platform:officialVoice:read") @GetMapping("/readiness")
    public AjaxResult readiness() { return AjaxResult.success(service.readiness()); }
    @RequiresPermissions("platform:officialVoice:read") @GetMapping("/tasks")
    public AjaxResult tasks(@RequestParam(required=false) Integer pageNum,@RequestParam(required=false) Integer pageSize,
        @RequestParam(required=false) Long taskId,@RequestParam(required=false) String status)
    { return AjaxResult.success(pageNum==null && pageSize==null && taskId==null && status==null?service.diagnostics():
        service.pageDiagnostics(pageNum==null?1:pageNum,pageSize==null?20:pageSize,taskId,status)); }
    @RequiresPermissions("platform:officialVoice:read") @GetMapping("/references")
    public AjaxResult references() { return AjaxResult.success(service.references(SecurityUtils.getUserId())); }
    @RequiresPermissions("platform:officialVoice:write") @PostMapping("/references")
    public AjaxResult upload(@RequestParam("file") MultipartFile file) { return AjaxResult.success(java.util.Map.of("referenceAssetId",service.upload(SecurityUtils.getUserId(),file))); }
}
