package com.ruoyi.system.developer.openapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.developer.access.dto.ManagementKeyRequest;
import com.ruoyi.system.developer.access.dto.ApplicationSecretRequest;
import com.ruoyi.system.developer.access.service.IAccessKeyService;

@RestController
@RequestMapping("/openapi/v1/management")
public class ManagementAccessKeyController
{
    private final IAccessKeyService keys;
    public ManagementAccessKeyController(IAccessKeyService keys) { this.keys = keys; }

    @GetMapping("/access-keys")
    public AjaxResult list(HttpServletRequest request)
    { var caller = principal(request); return AjaxResult.success(keys.list(caller.accountId(), "MANAGEMENT", null)); }

    @PostMapping("/access-keys")
    public AjaxResult create(HttpServletRequest request, @Valid @RequestBody ManagementKeyRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { var caller = principal(request); return AjaxResult.success(keys.createManagement(caller.accountId(), input.name(), input.scopes(), key, caller)); }

    @PostMapping("/access-keys/{keyId}/rotations")
    public AjaxResult rotate(HttpServletRequest request, @PathVariable long keyId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { var caller = principal(request); return AjaxResult.success(keys.rotateManagement(caller.accountId(), keyId, key, caller)); }

    @PostMapping("/access-keys/{keyId}/disable")
    public AjaxResult disable(HttpServletRequest request, @PathVariable long keyId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { var caller = principal(request); return AjaxResult.success(keys.changeStatus(caller.accountId(), keyId, "DISABLED", key, caller)); }

    @PostMapping("/access-keys/{keyId}/delete")
    public AjaxResult delete(HttpServletRequest request, @PathVariable long keyId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { var caller = principal(request); return AjaxResult.success(keys.changeStatus(caller.accountId(), keyId, "DELETED", key, caller)); }

    @GetMapping("/applications/{applicationId}/secrets")
    public AjaxResult application(HttpServletRequest request, @PathVariable long applicationId)
    { return AjaxResult.success(keys.list(principal(request).accountId(), "APPLICATION", applicationId)); }

    @PostMapping("/applications/{applicationId}/secrets/reset")
    public AjaxResult reset(HttpServletRequest request, @PathVariable long applicationId, @Valid @RequestBody ApplicationSecretRequest input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { var caller = principal(request); return AjaxResult.success(keys.resetApplication(caller.accountId(), applicationId, input.name(), key, caller)); }

    @PostMapping("/applications/{applicationId}/secrets/{keyId}/disable")
    public AjaxResult disableApplication(HttpServletRequest request, @PathVariable long applicationId, @PathVariable long keyId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { var caller = principal(request); return AjaxResult.success(keys.changeApplicationStatus(caller.accountId(), applicationId, keyId, "DISABLED", key, caller)); }

    @PostMapping("/applications/{applicationId}/secrets/{keyId}/delete")
    public AjaxResult deleteApplication(HttpServletRequest request, @PathVariable long applicationId, @PathVariable long keyId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { var caller = principal(request); return AjaxResult.success(keys.changeApplicationStatus(caller.accountId(), applicationId, keyId, "DELETED", key, caller)); }

    private static IAccessKeyService.Principal principal(HttpServletRequest request)
    {
        Object value = request.getAttribute(ManagementKeyFilter.PRINCIPAL);
        if (!(value instanceof IAccessKeyService.Principal principal)) throw new ServiceException("接入凭证无效", 401);
        return principal;
    }
}
