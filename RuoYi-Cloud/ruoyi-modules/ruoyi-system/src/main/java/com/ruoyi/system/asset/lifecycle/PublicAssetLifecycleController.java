package com.ruoyi.system.asset.lifecycle;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Browser-facing admin routes; the service validates kind and all state transitions. */
@RestController
@RequestMapping("/api/v1/admin")
public class PublicAssetLifecycleController
{
    private final PublicAssetLifecycleService lifecycle;
    public PublicAssetLifecycleController(PublicAssetLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @RequiresPermissions("platform:asset:read") @GetMapping("/public-avatars/{resourceId}")
    public AjaxResult detail(@PathVariable long resourceId) { administrator(); return AjaxResult.success(lifecycle.detail("avatars",resourceId)); }
    @RequiresPermissions("platform:asset:read") @GetMapping("/{kind:public-avatars|public-voices}/{resourceId}/references")
    public AjaxResult references(@PathVariable String kind,@PathVariable long resourceId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) { administrator(); return AjaxResult.success(lifecycle.references(kind.substring(7),resourceId,pageNum,pageSize)); }
    @RequiresPermissions("platform:asset:manage") @PostMapping("/{kind:public-avatars|public-voices}/{resourceId}/unpublish")
    public AjaxResult unpublish(@PathVariable String kind,@PathVariable long resourceId,@RequestBody Reason body,@RequestHeader("If-Match") String ifMatch,@RequestHeader("Idempotency-Key") String key) { return AjaxResult.success(lifecycle.unpublish(administrator(),kind.substring(7),resourceId,body == null ? null : body.reason(),ifMatch,key)); }
    @RequiresPermissions("platform:asset:manage") @PostMapping("/{kind:public-avatars|public-voices}/{resourceId}/disable")
    public AjaxResult disable(@PathVariable String kind,@PathVariable long resourceId,@RequestBody Disable body,@RequestHeader("If-Match") String ifMatch,@RequestHeader("Idempotency-Key") String key) { return AjaxResult.success(lifecycle.disable(administrator(),kind.substring(7),resourceId,body == null ? null : body.reason(),body != null && body.acknowledgeImpact(),ifMatch,key)); }
    @RequiresPermissions("platform:asset:manage") @DeleteMapping("/{kind:public-avatars|public-voices}/{resourceId}")
    public AjaxResult delete(@PathVariable String kind,@PathVariable long resourceId,@RequestHeader("If-Match") String ifMatch,@RequestHeader("Idempotency-Key") String key) { return AjaxResult.success(lifecycle.requestDelete(administrator(),kind.substring(7),resourceId,ifMatch,key)); }
    @RequiresPermissions("platform:asset:manage") @PostMapping("/{kind:public-avatars|public-voices}/{resourceId}/cleanup-retries")
    public AjaxResult retry(@PathVariable String kind,@PathVariable long resourceId,@RequestHeader("If-Match") String ifMatch,@RequestHeader("Idempotency-Key") String key) { return AjaxResult.success(lifecycle.retryCleanup(administrator(),kind.substring(7),resourceId,ifMatch,key)); }
    private static long administrator() { if (!SecurityUtils.isAdmin() || SecurityUtils.getUserId()==null || SecurityUtils.getUserId()<=0) throw new ServiceException("仅管理员可管理公共资产",403); return SecurityUtils.getUserId(); }
    public record Reason(String reason) { } public record Disable(String reason,boolean acknowledgeImpact) { }
}
