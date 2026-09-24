package com.ruoyi.system.developer.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.developer.access.mapper.AccessKeyMapper;
import com.ruoyi.system.developer.access.service.impl.AccessKeyServiceImpl;

class AccessKeyServiceTest
{
    @Test
    void applicationResetInvalidatesOnlyTheOldSecretAndNeverStoresItsPlaintext()
    {
        AccessKeyMapper mapper = mock(AccessKeyMapper.class);
        AtomicLong ids = new AtomicLong(100), active = new AtomicLong();
        Map<Long, Map<String, Object>> rows = new HashMap<>();
        Map<String, Map<String, Object>> byPublicId = new HashMap<>();
        when(mapper.nextId()).thenAnswer(call -> ids.incrementAndGet());
        when(mapper.accountForUpdate(7)).thenReturn(Map.of("status", "0", "delFlag", "0"));
        when(mapper.applicationForUpdate(7, 9)).thenReturn(Map.of("status", "ACTIVE", "adminDisabled", 0));
        when(mapper.account(7)).thenReturn(Map.of("status", "0", "delFlag", "0"));
        when(mapper.activeApplication(9)).thenAnswer(call -> active.get() == 0 ? null : Map.of("id", active.get()));
        when(mapper.idempotency(anyLong(), any(), any())).thenReturn(null);
        when(mapper.summary(eq(7L), anyLong())).thenAnswer(call -> rows.get(call.getArgument(1)));
        when(mapper.byPublicId(any())).thenAnswer(call -> byPublicId.get(call.getArgument(0)));
        doAnswer(call -> {
            long id = call.getArgument(0);
            String publicId = call.getArgument(5);
            byte[] hash = call.getArgument(6);
            rows.put(id, Map.of("keyId", Long.toString(id), "status", "ACTIVE"));
            Map<String, Object> row = new HashMap<>();
            row.put("id", id); row.put("accountId", 7L); row.put("applicationId", 9L);
            row.put("keyType", "APPLICATION"); row.put("secretHash", hash); row.put("hashKeyVersion", "hmac-sha256-v1");
            row.put("scopes", call.getArgument(8)); row.put("status", "ACTIVE"); row.put("authEpoch", 1L);
            row.put("accountStatus", "0"); row.put("accountDeleted", "0"); row.put("applicationStatus", "ACTIVE"); row.put("adminDisabled", 0);
            byPublicId.put(publicId, row);
            active.set(id);
            return null;
        }).when(mapper).insert(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        when(mapper.changeStatus(anyLong(), any())).thenAnswer(call -> {
            long id = call.getArgument(0);
            byPublicId.values().stream().filter(row -> ((Long) row.get("id")) == id).forEach(row -> row.put("status", call.getArgument(1)));
            return 1;
        });
        AccessKeyServiceImpl service = new AccessKeyServiceImpl(mapper, new ObjectMapper(), "0123456789abcdef0123456789abcdef");

        String first = (String) service.resetApplication(7, 9, "App A", "first", null).get("secret");
        assertTrue(first.startsWith("lna_"));
        assertEquals(9L, service.authenticate("Bearer " + first, "APPLICATION", "sessions:grant").applicationId());
        String second = (String) service.resetApplication(7, 9, "App A", "second", null).get("secret");
        assertThrows(ServiceException.class, () -> service.authenticate("Bearer " + first, "APPLICATION", "sessions:grant"));
        assertThrows(ServiceException.class, () -> service.authenticate("Bearer " + second, "MANAGEMENT", "keys:write"));
        assertEquals(9L, service.authenticate("Bearer " + second, "APPLICATION", "sessions:grant").applicationId());
        assertTrue(rows.values().stream().noneMatch(row -> row.containsKey("secret")));
    }
}
