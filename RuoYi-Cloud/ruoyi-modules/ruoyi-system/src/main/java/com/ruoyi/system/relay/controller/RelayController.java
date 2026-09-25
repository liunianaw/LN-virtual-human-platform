package com.ruoyi.system.relay.controller;

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
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.relay.service.IRelayService;

@RestController
@RequestMapping("/api/v1/developer/relay-services")
public class RelayController
{
    private final IRelayService relays;
    public RelayController(IRelayService relays) { this.relays = relays; }

    @RequiresPermissions("platform:relay:read")
    @GetMapping public AjaxResult list(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { return AjaxResult.success(relays.list(account(), pageNum, pageSize)); }

    @RequiresPermissions("platform:relay:read")
    @GetMapping("/{relayId}") public AjaxResult detail(@PathVariable long relayId)
    { return AjaxResult.success(relays.detail(account(), relayId)); }

    @RequiresPermissions("platform:relay:write")
    @PostMapping public AjaxResult create(@Valid @RequestBody IRelayService.CreateInput input,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(); return AjaxResult.success(relays.create(accountId, accountId, input, key)); }

    @RequiresPermissions("platform:relay:write")
    @PostMapping("/{relayId}/versions") public AjaxResult version(@PathVariable long relayId,
        @Valid @RequestBody IRelayService.VersionInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { long accountId = account(); return AjaxResult.success(relays.addVersion(accountId, accountId, relayId, input, ifMatch, key)); }

    @RequiresPermissions("platform:relay:write")
    @PutMapping("/{relayId}/grants") public AjaxResult grants(@PathVariable long relayId,
        @Valid @RequestBody IRelayService.GrantsInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.replaceGrants(account(), relayId, input, ifMatch, key)); }

    @RequiresPermissions("platform:relay:write")
    @PostMapping("/{relayId}/token") public AjaxResult token(@PathVariable long relayId,
        @Valid @RequestBody IRelayService.TokenInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.rotateToken(account(), relayId, input, ifMatch, key)); }

    @RequiresPermissions("platform:relay:write")
    @PostMapping("/{relayId}/status") public AjaxResult status(@PathVariable long relayId,
        @Valid @RequestBody IRelayService.StatusInput input, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.changeStatus(account(), relayId, input, ifMatch, key)); }

    @RequiresPermissions("platform:relay:write")
    @PostMapping("/{relayId}/connection-test") public AjaxResult test(@PathVariable long relayId,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.testConnection(account(), relayId, key)); }

    @RequiresPermissions("platform:relay:write")
    @DeleteMapping("/{relayId}") public AjaxResult delete(@PathVariable long relayId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return AjaxResult.success(relays.delete(account(), relayId, ifMatch, key)); }

    private static long account()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getToken() == null || login.getUserid() == null || login.getUserid() <= 0)
            throw new ServiceException("后台登录无效", 401);
        return login.getUserid();
    }
}
