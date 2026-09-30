package com.ruoyi.system.developer.openapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.developer.webhook.service.IWebhookService;

@RestController
@RequestMapping("/openapi/v1/management/webhook-endpoints")
public class ManagementWebhookController
{
    private final IWebhookService service;
    public ManagementWebhookController(IWebhookService service) { this.service = service; }
    @GetMapping public AjaxResult list(HttpServletRequest request, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.list(account(request), pageNum, pageSize)); }
    @PostMapping public AjaxResult create(HttpServletRequest request, @Valid @RequestBody IWebhookService.CreateInput input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(service.create(account(request), input, key)); }
    @PostMapping("/{endpointId}/secret") public AjaxResult rotate(HttpServletRequest request, @PathVariable long endpointId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(service.rotate(account(request), endpointId, ifMatch, key)); }
    @PostMapping("/{endpointId}/status") public AjaxResult status(HttpServletRequest request, @PathVariable long endpointId,
        @Valid @RequestBody IWebhookService.StatusInput input,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(service.status(account(request), endpointId, input, ifMatch, key)); }
    @GetMapping("/{endpointId}/deliveries") public AjaxResult deliveries(HttpServletRequest request, @PathVariable long endpointId,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.deliveries(account(request), endpointId, from, to, pageNum, pageSize)); }
    @GetMapping("/deliveries/{deliveryId}/attempts") public AjaxResult attempts(HttpServletRequest request, @PathVariable long deliveryId,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(service.attempts(account(request), deliveryId, from, to, pageNum, pageSize)); }
    private static long account(HttpServletRequest request)
    {
        Object principal = request.getAttribute(ManagementKeyFilter.PRINCIPAL);
        if (principal instanceof IAccessKeyService.Principal key && "MANAGEMENT".equals(key.type()) && key.accountId() > 0)
            return key.accountId();
        throw new ServiceException("管理 Key 身份无效", 401);
    }
}
