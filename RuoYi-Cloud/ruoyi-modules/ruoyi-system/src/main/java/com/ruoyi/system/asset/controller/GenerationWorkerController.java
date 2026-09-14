package com.ruoyi.system.asset.controller;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.asset.service.MediaInternalTokenGuard;
import com.ruoyi.system.asset.service.GenerationWorkerService;

/** 仅供持有环境注入内部令牌的媒体进程调用；此路径不得由网关公开。 */
@RestController
@RequestMapping("/asset/internal/generation")
public class GenerationWorkerController
{
    private final GenerationWorkerService workerService;
    private final MediaInternalTokenGuard tokenGuard;

    public GenerationWorkerController(GenerationWorkerService workerService, MediaInternalTokenGuard tokenGuard)
    {
        this.workerService = workerService;
        this.tokenGuard = tokenGuard;
    }

    @PostMapping("/claim")
    public AjaxResult claim(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token, @RequestBody ClaimRequest request)
    {
        tokenGuard.require(token);
        return AjaxResult.success(workerService.claim(request.accountId(), request.taskId(), request.workerId()));
    }

    @PostMapping("/outbox/claim")
    public AjaxResult claimOutbox(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token, @RequestBody WorkerRequest request)
    {
        tokenGuard.require(token);
        return AjaxResult.success(workerService.claimOutbox(request.workerId()));
    }

    @PostMapping("/outbox/sent")
    public AjaxResult markOutboxSent(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token, @RequestBody OutboxSentRequest request)
    {
        tokenGuard.require(token);
        workerService.markOutboxSent(request.outboxId(), request.workerId());
        return AjaxResult.success();
    }

    @PostMapping("/attempts")
    public AjaxResult prepareAttempt(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token, @RequestBody AttemptRequest request)
    {
        tokenGuard.require(token);
        return AjaxResult.success(workerService.prepareAttempt(request.accountId(), request.taskId(), request.stepId(), request.workerId(), request.leaseEpoch(), request.requestHash()));
    }

    @PostMapping("/progress")
    public AjaxResult progress(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token, @RequestBody ProgressRequest request)
    {
        tokenGuard.require(token);
        workerService.progress(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.state(), request.providerRequestId(), request.errorCode());
        return AjaxResult.success();
    }

    @PostMapping("/results/succeeded")
    public AjaxResult submitSucceeded(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token, @RequestBody SucceededResultRequest request)
    {
        tokenGuard.require(token);
        workerService.submitSucceeded(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.objects(), request.manifest());
        return AjaxResult.success();
    }

    @PostMapping("/results/terminal")
    public AjaxResult submitTerminal(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token, @RequestBody TerminalResultRequest request)
    {
        tokenGuard.require(token);
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
