package com.ruoyi.system.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.operations.dto.AccountLimitsRequest;
import com.ruoyi.system.operations.dto.QuotaGrantRequest;
import com.ruoyi.system.operations.mapper.UsageMapper;
import com.ruoyi.system.operations.service.impl.UsageServiceImpl;

class UsageAdminServiceTest
{
    @Test
    void duplicateGrantKeepsOneBalanceChangeAndRejectsChangedPayload()
    {
        UsageMapper mapper = mock(UsageMapper.class);
        UsageServiceImpl service = new UsageServiceImpl(mapper);
        QuotaGrantRequest request = new QuotaGrantRequest("TTS_CHAR", 100L, "Demo 验收");
        when(mapper.lockActiveAccount(910105L)).thenReturn(910105L);
        when(mapper.grantByKey(910105L,"admin:grant:request-1"))
            .thenReturn(null,Map.of("quotaType","TTS_CHAR","units",100L,"reason","Demo 验收"));
        when(mapper.quotaBalanceForUpdate(910105L,"TTS_CHAR")).thenReturn(null);
        when(mapper.insertQuotaBalance(910105L,"TTS_CHAR",100L)).thenReturn(1);
        when(mapper.balances(910105L)).thenReturn(List.of(Map.of("quotaType","TTS_CHAR","grantedUnits",100L)));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class))
        {
            security.when(SecurityUtils::isAdmin).thenReturn(true);
            assertEquals(true,service.grantQuota(1L,910105L,request,"request-1").get("applied"));
            assertEquals(false,service.grantQuota(1L,910105L,request,"request-1").get("applied"));
            assertThrows(ServiceException.class, () -> service.grantQuota(1L,910105L,
                new QuotaGrantRequest("TTS_CHAR",101L,"Demo 验收"),"request-1"));
        }
        verify(mapper).insertQuotaBalance(910105L,"TTS_CHAR",100L);
        verify(mapper).insertGrantEntry(910105L,"TTS_CHAR","admin:grant:request-1",100L,1L,"Demo 验收");
    }

    @Test
    void staleLimitRevisionDoesNotOverwriteCurrentLimits()
    {
        UsageMapper mapper = mock(UsageMapper.class);
        UsageServiceImpl service = new UsageServiceImpl(mapper);
        when(mapper.lockActiveAccount(910105L)).thenReturn(910105L);
        when(mapper.limits(910105L)).thenReturn(Map.of("revision",3L));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class))
        {
            security.when(SecurityUtils::isAdmin).thenReturn(true);
            assertThrows(ServiceException.class, () -> service.configureLimits(910105L,
                new AccountLimitsRequest(2L,0L,20,0,2)));
        }
    }
}
