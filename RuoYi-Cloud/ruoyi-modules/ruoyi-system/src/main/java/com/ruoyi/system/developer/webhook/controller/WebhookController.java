package com.ruoyi.system.developer.webhook.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.developer.webhook.service.IWebhookService;

@RestController
@RequestMapping("/api/v1/developer/webhook-endpoints")
public class WebhookController
{
    private final IWebhookService service;
    public WebhookController(IWebhookService service) { this.service = service; }
    @RequiresPermissions("platform:webhook:read")
    @GetMapping public AjaxResult list(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.list(SecurityUtils.getUserId(), pageNum, pageSize)); }
    @RequiresPermissions("platform:webhook:write")
    @PostMapping public AjaxResult create(@Valid @RequestBody IWebhookService.CreateInput input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(service.create(SecurityUtils.getUserId(), input, key)); }
    @RequiresPermissions("platform:webhook:write")
    @PostMapping("/{endpointId}/secret") public AjaxResult rotate(@PathVariable long endpointId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(service.rotate(SecurityUtils.getUserId(), endpointId, ifMatch, key)); }
    @RequiresPermissions("platform:webhook:write")
    @PostMapping("/{endpointId}/status") public AjaxResult status(@PathVariable long endpointId,
        @Valid @RequestBody IWebhookService.StatusInput input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(service.status(SecurityUtils.getUserId(), endpointId, input, ifMatch, key)); }
    @RequiresPermissions("platform:webhook:read")
    @GetMapping("/{endpointId}/deliveries") public AjaxResult deliveries(@PathVariable long endpointId,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.deliveries(SecurityUtils.getUserId(), endpointId, from, to, pageNum, pageSize)); }
    @RequiresPermissions("platform:webhook:read")
    @GetMapping("/deliveries/{deliveryId}/attempts") public AjaxResult attempts(@PathVariable long deliveryId,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.attempts(SecurityUtils.getUserId(), deliveryId, from, to, pageNum, pageSize)); }
}
