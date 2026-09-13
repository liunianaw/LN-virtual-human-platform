package com.ruoyi.system.asset.controller;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.system.asset.service.GenerationWorkerService;

/** 仅供持有若依 worker 权限的内部媒体进程调用。 */
@RestController
@RequestMapping("/asset/internal/generation")
public class GenerationWorkerController
{
    private final GenerationWorkerService workerService;

    public GenerationWorkerController(GenerationWorkerService workerService) { this.workerService = workerService; }

    @RequiresPermissions("system:asset:worker")
    @PostMapping("/claim")
    public AjaxResult claim(@RequestBody ClaimRequest request)
    {
        return AjaxResult.success(workerService.claim(request.accountId(), request.taskId(), request.workerId()));
    }

    @RequiresPermissions("system:asset:worker")
    @PostMapping("/outbox/claim")
    public AjaxResult claimOutbox(@RequestBody WorkerRequest request) { return AjaxResult.success(workerService.claimOutbox(request.workerId())); }

    @RequiresPermissions("system:asset:worker")
    @PostMapping("/outbox/sent")
    public AjaxResult markOutboxSent(@RequestBody OutboxSentRequest request) { workerService.markOutboxSent(request.outboxId(), request.workerId()); return AjaxResult.success(); }

    @RequiresPermissions("system:asset:worker")
    @PostMapping("/attempts")
    public AjaxResult prepareAttempt(@RequestBody AttemptRequest request)
    {
        return AjaxResult.success(workerService.prepareAttempt(request.accountId(), request.taskId(), request.stepId(), request.workerId(), request.leaseEpoch(), request.requestHash()));
    }

    @RequiresPermissions("system:asset:worker")
    @PostMapping("/progress")
    public AjaxResult progress(@RequestBody ProgressRequest request)
    {
        workerService.progress(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.state(), request.providerRequestId(), request.errorCode());
        return AjaxResult.success();
    }

    @RequiresPermissions("system:asset:worker")
    @PostMapping("/results/succeeded")
    public AjaxResult submitSucceeded(@RequestBody SucceededResultRequest request)
    {
        workerService.submitSucceeded(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.objects(), request.manifest());
        return AjaxResult.success();
    }

    @RequiresPermissions("system:asset:worker")
    @PostMapping("/results/terminal")
    public AjaxResult submitTerminal(@RequestBody TerminalResultRequest request)
    {
        workerService.submitTerminal(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.state());
        return AjaxResult.success();
    }

    public record ClaimRequest(Long accountId, Long taskId, String workerId) { }
    public record WorkerRequest(String workerId) { }
    public record OutboxSentRequest(Long outboxId, String workerId) { }
    public record AttemptRequest(Long accountId, Long taskId, Long stepId, String workerId, Long leaseEpoch, String requestHash) { }
    public record ProgressRequest(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
                                  String state, String providerRequestId, String errorCode) { }
    public record SucceededResultRequest(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
                                         List<GenerationWorkerService.StoredObject> objects, Map<String, Object> manifest) { }
    public record TerminalResultRequest(Long accountId, Long taskId, Long stepId, Long attemptId, String workerId, Long leaseEpoch,
                                        String state) { }
}
