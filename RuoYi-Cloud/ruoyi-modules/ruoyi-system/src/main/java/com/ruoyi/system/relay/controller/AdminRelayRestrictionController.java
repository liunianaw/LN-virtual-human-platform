package com.ruoyi.system.relay.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.relay.service.IRelayService;

@RestController
@RequestMapping("/api/v1/admin/relay-services")
public class AdminRelayRestrictionController
{
    public record Restriction(boolean disabled, String reason) { }
    private final IRelayService relays;
    public AdminRelayRestrictionController(IRelayService relays) { this.relays = relays; }
    @RequiresPermissions("platform:relay:admin-disable")
    @PostMapping("/{relayId}/restriction")
    public AjaxResult change(@PathVariable long relayId, @RequestBody Restriction input)
    {
        if (!SecurityUtils.isAdmin() || SecurityUtils.getUserId() == null) throw new ServiceException("仅管理员可限制 Relay", 403);
        return AjaxResult.success(relays.changeAdminDisabled(SecurityUtils.getUserId(), relayId,
            input.disabled(), input.reason()));
    }
}
