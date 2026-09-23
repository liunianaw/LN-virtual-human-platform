package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.api.domain.RegisterRequest;
import com.ruoyi.system.api.domain.SysUser;

@ExtendWith(MockitoExtension.class)
class AccountRegistrationServiceTest
{
    @Mock private JdbcTemplate jdbc;
    @Mock private ISysUserService users;
    @Mock private ObjectProvider<org.springframework.mail.javamail.JavaMailSender> mailSenders;

    @Test
    void wrongEmailCodeDoesNotCreateAnAccount()
    {
        when(users.checkUserNameUnique(any(SysUser.class))).thenReturn(true);
        when(users.checkEmailUnique(any(SysUser.class))).thenReturn(true);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
            "id", 1L,
            "token_hash", new byte[32],
            "expires_at", LocalDateTime.now().plusSeconds(60),
            "failed_attempts", 0)));
        AccountRegistrationService service = new AccountRegistrationService(jdbc, users, mailSenders);
        RegisterRequest request = new RegisterRequest();
        request.setUsername("developer");
        request.setEmail("developer@example.test");
        request.setPassword("encoded-password");
        request.setEmailCode("123456");

        assertThrows(ServiceException.class, () -> service.register(request));
        verify(users, never()).registerUser(any(SysUser.class));
    }
}
