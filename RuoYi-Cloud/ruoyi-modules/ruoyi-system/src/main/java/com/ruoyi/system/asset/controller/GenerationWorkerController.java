package com.ruoyi.system.asset.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.asset.service.MediaInternalTokenGuard;
import com.ruoyi.system.asset.dto.GenerationAttemptRequest;
import com.ruoyi.system.asset.dto.GenerationClaimRequest;
import com.ruoyi.system.asset.dto.GenerationPreflightReleaseRequest;
import com.ruoyi.system.asset.dto.GenerationProgressRequest;
import com.ruoyi.system.asset.dto.GenerationReceiptRequest;
import com.ruoyi.system.asset.dto.GenerationSucceededResultRequest;
import com.ruoyi.system.asset.dto.GenerationTaskActiveRequest;
import com.ruoyi.system.asset.dto.GenerationTerminalResultRequest;
import com.ruoyi.system.asset.service.IGenerationWorkerService;

/** 仅供持有环境注入内部令牌的媒体进程调用；此路径不得由网关公开。 */
@RestController
@RequestMapping("/asset/internal/generation")
public class GenerationWorkerController
{
    private final IGenerationWorkerService workerService;
    private final MediaInternalTokenGuard tokenGuard;

    public GenerationWorkerController(IGenerationWorkerService workerService, MediaInternalTokenGuard tokenGuard)
    {
        this.workerService = workerService;
        this.tokenGuard = tokenGuard;
    }

    @PostMapping("/claim")
    public AjaxResult claim(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationClaimRequest request)
    {
        tokenGuard.require(token);
        return AjaxResult.success(workerService.claim(request.accountId(), request.taskId(), request.workerId()));
    }

    @PostMapping("/task-active")
    public AjaxResult taskActive(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationTaskActiveRequest request)
    {
        tokenGuard.require(token);
        return AjaxResult.success(workerService.hasActiveSteps(request.accountId(), request.taskId()));
    }

    @PostMapping("/claim-release")
    public AjaxResult releasePreflightClaim(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationPreflightReleaseRequest request)
    {
        tokenGuard.require(token);
        workerService.releasePreflightClaim(request.accountId(), request.taskId(), request.stepId(), request.workerId(), request.leaseEpoch(), request.errorCode());
        return AjaxResult.success();
    }

    @PostMapping("/attempts")
    public AjaxResult prepareAttempt(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationAttemptRequest request)
    {
        tokenGuard.require(token);
        return AjaxResult.success(workerService.prepareAttempt(request.accountId(), request.taskId(), request.stepId(), request.workerId(), request.leaseEpoch(), request.requestHash()));
    }

    @PostMapping("/progress")
    public AjaxResult progress(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationProgressRequest request)
    {
        tokenGuard.require(token);
        workerService.progress(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.state(), request.providerRequestId(), request.errorCode());
        return AjaxResult.success();
    }

    @PostMapping("/receipt")
    public AjaxResult receipt(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationReceiptRequest request)
    {
        tokenGuard.require(token);
        workerService.saveReceipt(request.accountId(), request.taskId(), request.stepId(), request.attemptId(),
            request.workerId(), request.leaseEpoch(), request.receipt());
        return AjaxResult.success();
    }

    @PostMapping("/results/succeeded")
    public AjaxResult submitSucceeded(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationSucceededResultRequest request)
    {
        tokenGuard.require(token);
        workerService.submitSucceeded(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.objects(), request.manifest());
        return AjaxResult.success();
    }

    @PostMapping("/results/terminal")
    public AjaxResult submitTerminal(@RequestHeader(value = "X-LN-Internal-Token", required = false) String token,
        @Valid @RequestBody GenerationTerminalResultRequest request)
    {
        tokenGuard.require(token);
        workerService.submitTerminal(request.accountId(), request.taskId(), request.stepId(), request.attemptId(), request.workerId(),
            request.leaseEpoch(), request.state());
        return AjaxResult.success();
    }

}
