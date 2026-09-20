package com.ruoyi.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.constant.CacheConstants;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.redis.service.RedisService;
import com.ruoyi.system.api.RemoteUserService;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.api.model.LoginUser;

class SysLoginServiceIdentifierTest
{
    private static final String PASSWORD = "valid-password";

    private static final String USER_NAME = "m1devread";

    private static final String EMAIL = "m1devread@local.invalid";

    private SysLoginService loginService;

    private RemoteUserService remoteUserService;

    @BeforeEach
    void setUp()
    {
        loginService = new SysLoginService();
        remoteUserService = mock(RemoteUserService.class);
        RedisService redisService = mock(RedisService.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ReflectionTestUtils.setField(loginService, "remoteUserService", remoteUserService);
        ReflectionTestUtils.setField(loginService, "passwordService", mock(SysPasswordService.class));
        ReflectionTestUtils.setField(loginService, "recordLogService", mock(SysRecordLogService.class));
        ReflectionTestUtils.setField(loginService, "redisService", redisService);
        ReflectionTestUtils.setField(loginService, "validator", validator);

        when(redisService.getCacheObject(CacheConstants.SYS_LOGIN_BLACKIPLIST)).thenReturn(null);
        when(remoteUserService.getUserInfo(any(String.class), eq(SecurityConstants.INNER))).thenReturn(R.ok(loginUser()));
        when(remoteUserService.recordUserLogin(any(SysUser.class), eq(SecurityConstants.INNER))).thenReturn(R.ok(true));
    }

    @Test
    void acceptsAUsernameWithinTheExistingRange()
    {
        assertEquals(USER_NAME, loginService.login(USER_NAME, PASSWORD).getSysUser().getUserName());

        verify(remoteUserService).getUserInfo(USER_NAME, SecurityConstants.INNER);
    }

    @Test
    void acceptsAValidEmailIdentifier()
    {
        assertEquals(USER_NAME, loginService.login(EMAIL, PASSWORD).getSysUser().getUserName());

        verify(remoteUserService).getUserInfo(EMAIL, SecurityConstants.INNER);
    }

    @Test
    void rejectsAnOverlongEmailIdentifier()
    {
        String overlongEmail = "a".repeat(40) + "@example.test";

        assertThrows(ServiceException.class, () -> loginService.login(overlongEmail, PASSWORD));

        verifyNoInteractions(remoteUserService);
    }

    @Test
    void rejectsAnOverlongNonEmailIdentifier()
    {
        String invalidIdentifier = "not-an-email-identifier-over-twenty";

        assertThrows(ServiceException.class, () -> loginService.login(invalidIdentifier, PASSWORD));

        verifyNoInteractions(remoteUserService);
    }

    private LoginUser loginUser()
    {
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName(USER_NAME);
        LoginUser loginUser = new LoginUser();
        loginUser.setSysUser(user);
        return loginUser;
    }
}
