package com.ruoyi.system.developer.access.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.InnerAuth;
import com.ruoyi.system.developer.access.service.IAccessKeyService;

/** Used by the BUSINESS Session service from DEV-06; no public gateway route. */
@RestController
@RequestMapping("/internal/developer/access-keys")
public class InternalAccessKeyController
{
    private final IAccessKeyService keys;
    public InternalAccessKeyController(IAccessKeyService keys) { this.keys = keys; }

    @InnerAuth
    @PostMapping("/application-principal")
    public AjaxResult application(@RequestHeader("Authorization") String authorization)
    { return AjaxResult.success(keys.authenticate(authorization, "APPLICATION", "sessions:grant")); }
}
