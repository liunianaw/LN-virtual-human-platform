package com.ruoyi.system.operations;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.system.asset.service.MediaInternalTokenGuard;
import com.ruoyi.system.voice.InternalBearerGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin diagnosis APIs and a private calling-fact ingress. */
@RestController
public class OperationsController
{
    private final OperationsService operations;
    private final InternalBearerGuard sessionGuard;
    private final MediaInternalTokenGuard mediaGuard;

    public OperationsController(OperationsService operations, InternalBearerGuard sessionGuard, MediaInternalTokenGuard mediaGuard)
    { this.operations = operations; this.sessionGuard = sessionGuard; this.mediaGuard = mediaGuard; }

    @RequiresPermissions("platform:operations:read")
    @GetMapping("/api/v1/admin/generation-tasks")
    public AjaxResult tasks(@RequestParam(required = false) String accountId, @RequestParam(required = false) String status,
        @RequestParam(required = false) String errorCode, @RequestParam(required = false) String from,
        @RequestParam(required = false) String to, @RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize)
    { administrator(); return AjaxResult.success(operations.tasks(accountId, status, errorCode, from, to, pageNum, pageSize)); }

    @RequiresPermissions("platform:operations:read")
    @GetMapping("/api/v1/admin/generation-tasks/{taskId}")
    public AjaxResult task(@PathVariable long taskId)
    { administrator(); return AjaxResult.success(operations.task(taskId)); }

    @Log(title = "生成尝试核对", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @RequiresPermissions("platform:operations:reconcile")
    @PostMapping("/api/v1/admin/generation-tasks/{taskId}/attempts/{attemptId}/reconciliations")
    public AjaxResult reconcile(@PathVariable long taskId, @PathVariable long attemptId, @RequestBody Reconciliation body,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(operations.reconcile(administrator().getUserid(), taskId, attemptId, body == null ? null : body.reason(), ifMatch, key)); }

    @RequiresPermissions("platform:operations:read")
    @GetMapping("/api/v1/admin/call-records")
    public AjaxResult calls(@RequestParam(required = false) String accountId, @RequestParam(required = false) String capability,
        @RequestParam(required = false) String status, @RequestParam(required = false) String taskId,
        @RequestParam(required = false) String sessionId, @RequestParam(required = false) String from,
        @RequestParam(required = false) String to, @RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize)
    { administrator(); return AjaxResult.success(operations.calls(accountId, capability, status, taskId, sessionId, from, to, pageNum, pageSize)); }

    @RequiresPermissions("platform:operations:read")
    @GetMapping("/api/v1/admin/call-records/{callId}")
    public AjaxResult call(@PathVariable long callId)
    { administrator(); return AjaxResult.success(operations.call(callId)); }

    @Log(title = "调用事实人工核对", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @RequiresPermissions("platform:operations:reconcile")
    @PostMapping("/api/v1/admin/call-records/{callId}/reviews")
    public AjaxResult review(@PathVariable long callId, @RequestBody CallReview body,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(operations.review(administrator().getUserid(), callId, body, ifMatch, key)); }

    @RequiresPermissions("platform:operations:read")
    @GetMapping("/api/v1/admin/operations/{operationId}")
    public AjaxResult operation(@PathVariable long operationId)
    { administrator(); return AjaxResult.success(operations.operation(operationId)); }

    /** No gateway route is permitted for this endpoint. */
    @PostMapping("/internal/v1/call-records/events")
    public AjaxResult event(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-LN-Internal-Token", required = false) String mediaToken,
        @RequestBody CallFactEvent event)
    {
        if (mediaToken != null) mediaGuard.require(mediaToken); else sessionGuard.requireSession(authorization);
        return AjaxResult.success(operations.accept(event));
    }

    private static LoginUser administrator()
    {
        LoginUser login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null || !SecurityUtils.isAdmin())
            throw new ServiceException("仅管理员可查看或核对平台任务", 403);
        return login;
    }

    public record Reconciliation(String reason) { }
    public record CallReview(String reviewedStatus, String evidenceNote, java.math.BigDecimal costAmount,
        String currency, String costSource) { }
}
