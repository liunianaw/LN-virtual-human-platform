package com.ruoyi.system.developer.openapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.relay.service.IRelayService;

/** Management Key calls the same account-owned Relay service as the console. */
@RestController
@RequestMapping("/openapi/v1/management/relay-services")
public class ManagementRelayController
{
    private final IRelayService relays;
    public ManagementRelayController(IRelayService relays) { this.relays = relays; }
    @GetMapping public AjaxResult list(HttpServletRequest request, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(relays.list(account(request), pageNum, pageSize)); }
    @GetMapping("/{relayId}") public AjaxResult detail(HttpServletRequest request, @PathVariable long relayId)
    { return AjaxResult.success(relays.detail(account(request), relayId)); }
    @PostMapping public AjaxResult create(HttpServletRequest request, @Valid @RequestBody IRelayService.CreateInput input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(request); return AjaxResult.success(relays.create(accountId, accountId, input, key)); }
    @PostMapping("/{relayId}/versions") public AjaxResult version(HttpServletRequest request, @PathVariable long relayId,
        @Valid @RequestBody IRelayService.VersionInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(request); return AjaxResult.success(relays.addVersion(accountId, accountId, relayId, input, ifMatch, key)); }
    @PutMapping("/{relayId}/grants") public AjaxResult grants(HttpServletRequest request, @PathVariable long relayId,
        @Valid @RequestBody IRelayService.GrantsInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.replaceGrants(account(request), relayId, input, ifMatch, key)); }
    @PostMapping("/{relayId}/token") public AjaxResult token(HttpServletRequest request, @PathVariable long relayId,
        @Valid @RequestBody IRelayService.TokenInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.rotateToken(account(request), relayId, input, ifMatch, key)); }
    @PostMapping("/{relayId}/status") public AjaxResult status(HttpServletRequest request, @PathVariable long relayId,
        @Valid @RequestBody IRelayService.StatusInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.changeStatus(account(request), relayId, input, ifMatch, key)); }
    @PostMapping("/{relayId}/connection-test") public AjaxResult test(HttpServletRequest request, @PathVariable long relayId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.testConnection(account(request), relayId, key)); }
    @DeleteMapping("/{relayId}") public AjaxResult delete(HttpServletRequest request, @PathVariable long relayId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.delete(account(request), relayId, ifMatch, key)); }

    private static long account(HttpServletRequest request)
    {
        Object principal = request.getAttribute(ManagementKeyFilter.PRINCIPAL);
        if (principal instanceof IAccessKeyService.Principal key && "MANAGEMENT".equals(key.type()) && key.accountId() > 0)
            return key.accountId();
        throw new ServiceException("管理 Key 身份无效", 401);
    }
}
