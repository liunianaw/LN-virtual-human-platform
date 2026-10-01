package com.ruoyi.system.asset.controller;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.asset.service.IAvatarPublicationService;

/** Domain identities remain separate even when permissions contain a wildcard. */
final class AssetConsoleIdentity
{
    private AssetConsoleIdentity() { }
    static void console()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null) throw new ServiceException("当前后台登录无效", 401);
        if (!SecurityUtils.isAdmin() && (login.getRoles() == null || !login.getRoles().contains("developer")))
            throw new ServiceException("当前账号不能使用角色制作", 403);
    }
    static void developer()
    {
        var login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null) throw new ServiceException("当前后台登录无效", 401);
        if (SecurityUtils.isAdmin() || login.getRoles() == null || !login.getRoles().contains("developer"))
            throw new ServiceException("仅开发者可访问私有角色入口", 403);
    }
    static void administrator()
    {
        if (!SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可访问公共角色入口", 403);
    }
    static void avatar(IAvatarPublicationService service, Long avatarId)
    {
        if (SecurityUtils.isAdmin() && !"OFFICIAL".equals(service.detail(SecurityUtils.getUserId(), avatarId).get("visibility")))
            throw new ServiceException("管理员不能操作开发者私有角色", 403);
    }
}
