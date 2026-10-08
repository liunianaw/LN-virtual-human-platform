package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.api.domain.RegisterRequest;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.operations.mapper.PointBillingMapper;
import com.ruoyi.system.operations.mapper.UsageMapper;
import com.ruoyi.system.account.domain.RegistrationBenefits;

@ExtendWith(MockitoExtension.class)
class AccountRegistrationServiceTest
{
    @Mock private JdbcTemplate jdbc;
    @Mock private ISysUserService users;
    @Mock private ObjectProvider<org.springframework.mail.javamail.JavaMailSender> mailSenders;
    @Mock private UsageMapper quotas;
    @Mock private PointBillingMapper points;
    @Mock private ISysConfigService configs;

    @Test
    void wrongEmailCodeDoesNotCreateAnAccount()
    {
        when(users.checkUserNameUnique(any(SysUser.class))).thenReturn(true);
        when(users.checkEmailUnique(any(SysUser.class))).thenReturn(true);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
            "id", 1L,
            "token_hash", new byte[32],
            "expires_at", LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60),
            "failed_attempts", 0)));
        AccountRegistrationService service = service();
        RegisterRequest request = new RegisterRequest();
        request.setUsername("developer");
        request.setEmail("developer@example.test");
        request.setPassword("encoded-password");
        request.setEmailCode("123456");

        assertThrows(ServiceException.class, () -> service.register(request));
        verify(users, never()).registerUser(any(SysUser.class));
        verifyNoInteractions(quotas, points, configs);
    }

    @ParameterizedTest
    @CsvSource({ "2,100,100,104857600,10000", "3,200,150.25,209715200,15025" })
    void successfulRegistrationUsesAdministratorSettingsOnlyOnce(int concurrency, String storageMb, String pointAmount,
        long bytes, long cent) throws Exception
    {
        prepareRegistration();
        prepareBenefits(String.valueOf(concurrency), storageMb, pointAmount);
        when(quotas.insertLimits(42L, bytes, concurrency, concurrency, concurrency)).thenReturn(1);
        when(quotas.insertQuotaBalance(42L, "STORAGE_BYTE", bytes)).thenReturn(1);
        when(quotas.insertGrantEntry(42L, "STORAGE_BYTE", "registration:storage-grant", bytes, null,
            "新用户注册初始赠送")).thenReturn(1);
        when(points.insertBalance(42L, cent)).thenReturn(1);
        when(points.insertEntry(51L, 42L, null, "registration:point-grant", "GRANT", cent, 0L, 0L, null,
            "新用户注册初始赠送")).thenReturn(1);
        AccountRegistrationService service = service();
        service.register(request());
        when(quotas.grantByKey(42L, "registration:storage-grant")).thenReturn(Map.of("quotaType", "STORAGE_BYTE",
            "units", bytes, "reason", "新用户注册初始赠送"));
        when(points.grantByKey(42L, "registration:point-grant")).thenReturn(Map.of("amountCent", cent,
            "reason", "新用户注册初始赠送"));
        service.register(request());
        verify(quotas).insertLimits(42L, bytes, concurrency, concurrency, concurrency);
        verify(quotas).insertQuotaBalance(42L, "STORAGE_BYTE", bytes);
        verify(quotas).insertGrantEntry(42L, "STORAGE_BYTE", "registration:storage-grant", bytes, null,
            "新用户注册初始赠送");
        verify(points).insertBalance(42L, cent);
        verify(points).insertEntry(51L, 42L, null, "registration:point-grant", "GRANT", cent, 0L, 0L, null,
            "新用户注册初始赠送");
        verify(configs).selectConfigByKey(RegistrationBenefits.CONCURRENCY_KEY);
        verify(configs).selectConfigByKey(RegistrationBenefits.STORAGE_MB_KEY);
        verify(configs).selectConfigByKey(RegistrationBenefits.POINTS_KEY);
    }

    @Test
    void anExistingUserDoesNotGetBenefits()
    {
        when(users.checkUserNameUnique(any(SysUser.class))).thenReturn(false);
        assertThrows(ServiceException.class, () -> service().register(request()));
        verify(users, never()).registerUser(any(SysUser.class));
        verifyNoInteractions(jdbc, quotas, points, configs);
    }

    @Test
    void aFailedGrantFailsTheWholeRegistration() throws Exception
    {
        prepareRegistration();
        prepareBenefits("2", "100", "100");
        when(quotas.insertLimits(42L, 104857600L, 2, 2, 2)).thenReturn(0);
        assertThrows(ServiceException.class, () -> service().register(request()));
        verify(points, never()).insertBalance(anyLong(), anyLong());
    }

    private void prepareRegistration() throws Exception
    {
        when(users.checkUserNameUnique(any(SysUser.class))).thenReturn(true);
        when(users.checkEmailUnique(any(SysUser.class))).thenReturn(true);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("id", 1L,
            "token_hash", MessageDigest.getInstance("SHA-256").digest("123456".getBytes(StandardCharsets.UTF_8)),
            "expires_at", LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60), "failed_attempts", 0)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(users.registerUser(any(SysUser.class))).thenAnswer(call -> {
            ((SysUser) call.getArgument(0)).setUserId(42L);
            return true;
        });
        when(quotas.lockActiveAccount(42L)).thenReturn(42L);
        when(quotas.grantByKey(42L, "registration:storage-grant")).thenReturn(null);
        when(points.grantByKey(42L, "registration:point-grant")).thenReturn(null);
        when(quotas.limits(42L)).thenReturn(null);
        when(quotas.quotaBalanceForUpdate(42L, "STORAGE_BYTE")).thenReturn(null);
        when(points.balanceForUpdate(42L)).thenReturn(null);
        when(points.nextId()).thenReturn(51L);
    }

    private AccountRegistrationService service()
    {
        return new AccountRegistrationService(jdbc, users, mailSenders, quotas, points, configs);
    }

    private void prepareBenefits(String concurrency, String storageMb, String pointAmount)
    {
        when(configs.selectConfigByKey(RegistrationBenefits.CONCURRENCY_KEY)).thenReturn(concurrency);
        when(configs.selectConfigByKey(RegistrationBenefits.STORAGE_MB_KEY)).thenReturn(storageMb);
        when(configs.selectConfigByKey(RegistrationBenefits.POINTS_KEY)).thenReturn(pointAmount);
    }

    private RegisterRequest request()
    {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("developer");
        request.setEmail("developer@example.test");
        request.setPassword("encoded-password");
        request.setEmailCode("123456");
        return request;
    }
}
