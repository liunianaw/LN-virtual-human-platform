package com.ruoyi.system.controller;

import java.util.Map;
import com.ruoyi.system.storage.AccountIconService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.api.model.LoginUser;
import com.ruoyi.system.service.ISysUserService;

/**
 * 个人信息 业务处理
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/user/profile")
public class SysProfileController extends BaseController
{
    @Autowired
    private ISysUserService userService;
    
    @Autowired
    private TokenService tokenService;
    
    @Autowired
    private AccountIconService iconService;

    /**
     * 个人信息
     */
    @GetMapping
    public AjaxResult profile()
    {
        String username = SecurityUtils.getUsername();
        SysUser user = userService.selectUserByUserName(username);
        user.setAvatar(iconService.readUrl(user.getUserId(), user.getAvatar()));
        AjaxResult ajax = AjaxResult.success(user);
        ajax.put("roleGroup", userService.selectUserRoleGroup(username));
        return ajax;
    }

    /**
     * 修改用户
     */
    @Log(title = "个人信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult updateProfile(@RequestBody SysUser user)
    {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        SysUser currentUser = loginUser.getSysUser();
        currentUser.setNickName(user.getNickName());
        currentUser.setEmail(user.getEmail());
        currentUser.setPhonenumber(user.getPhonenumber());
        currentUser.setSex(user.getSex());
        if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(currentUser))
        {
            return error("修改用户'" + loginUser.getUsername() + "'失败，手机号码已存在");
        }
        if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(currentUser))
        {
            return error("修改用户'" + loginUser.getUsername() + "'失败，邮箱账号已存在");
        }
        if (userService.updateUserProfile(currentUser))
        {
            // 更新缓存用户信息
            tokenService.setLoginUser(loginUser);
            return success();
        }
        return error("修改个人信息异常，请联系管理员");
    }

    /**
     * 重置密码
     */
    @Log(title = "个人信息", businessType = BusinessType.UPDATE)
    @PutMapping("/updatePwd")
    public AjaxResult updatePwd(@RequestBody Map<String, String> params)
    {
        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Long userId = loginUser.getUserid();
        String password = loginUser.getSysUser().getPassword();
        if (!SecurityUtils.matchesPassword(oldPassword, password))
        {
            return error("修改密码失败，旧密码错误");
        }
        if (SecurityUtils.matchesPassword(newPassword, password))
        {
            return error("新密码不能与旧密码相同");
        }
        newPassword = SecurityUtils.encryptPassword(newPassword);
        if (userService.resetUserPwd(userId, newPassword) > 0)
        {
            // 更新缓存用户密码&密码最后更新时间
            loginUser.getSysUser().setPwdUpdateDate(DateUtils.getNowDate());
            loginUser.getSysUser().setPassword(newPassword);
            tokenService.setLoginUser(loginUser);
            return success();
        }
        return error("修改密码异常，请联系管理员");
    }
    
    /** 账号头像上传。对象键持久保存，签名地址只用于当前响应。 */
    @Log(title = "用户头像", businessType = BusinessType.UPDATE)
    @PostMapping("/avatar")
    public AjaxResult avatar(@RequestParam("avatarfile") MultipartFile file)
    {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        Long accountId = loginUser.getUserid();
        String oldKey = userService.selectUserById(accountId).getAvatar();
        String newKey = iconService.upload(accountId, file);
        // 数据库异常时不贸然删除新对象：写入是否成功可能未知，待文件账本接管后统一核对。
        if (!userService.updateUserAvatar(accountId, newKey))
        {
            cleanupIcon(accountId, newKey);
            return error("修改头像失败");
        }
        loginUser.getSysUser().setAvatar(newKey);
        tokenService.setLoginUser(loginUser);
        cleanupIcon(accountId, oldKey);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("imgUrl", iconService.readUrl(accountId, newKey));
        return ajax;
    }

    private void cleanupIcon(Long accountId, String key)
    {
        if (StringUtils.isEmpty(key)) return;
        try { iconService.delete(accountId, key); }
        catch (RuntimeException e)
        {
            // 不输出凭证、签名链接或上游响应，不让旧图清理失败覆盖已成功的头像更新。
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("账号 {} 的旧头像未清理", accountId);
        }
    }
}
