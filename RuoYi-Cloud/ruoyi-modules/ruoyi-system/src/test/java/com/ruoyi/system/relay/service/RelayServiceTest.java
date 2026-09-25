package com.ruoyi.system.relay.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.officialservice.service.OfficialSecretCrypto;
import com.ruoyi.system.relay.mapper.RelayMapper;
import com.ruoyi.system.relay.service.impl.RelayServiceImpl;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class RelayServiceTest
{
    private final RelayMapper mapper = mock(RelayMapper.class);
    private final OfficialSecretCrypto crypto = mock(OfficialSecretCrypto.class);
    private final RelayServiceImpl service = new RelayServiceImpl(mapper, crypto,
        mock(RelayTarget.class), mock(RelayCapabilityProbe.class), new ObjectMapper(), mock(PlatformTransactionManager.class));

    @Test void runtimeRejectsTtsAndForeignVersionBeforeSecretResolution()
    {
        assertThrows(ServiceException.class, () -> service.resolve(new IRelayService.RuntimeBinding(1, 2, 3, 4, "TTS", "user", 5L)));
        verifyNoInteractions(mapper, crypto);
        assertThrows(ServiceException.class, () -> service.resolve(new IRelayService.RuntimeBinding(1, 2, 3, 4, "LLM", "user", 5L)));
        verifyNoInteractions(crypto);
    }

    @Test void disabledRelayNeverReturnsItsToken()
    {
        when(mapper.version(1, 4)).thenReturn(Map.of("relayId", 8L, "capabilities", "{\"llm\":true}"));
        Map<String, Object> row = new HashMap<>();
        row.put("relayId", 8L); row.put("accountId", 1L); row.put("status", "DISABLED"); row.put("adminDisabled", 0);
        when(mapper.service(1, 8)).thenReturn(row);
        assertThrows(ServiceException.class, () -> service.resolve(new IRelayService.RuntimeBinding(1, 2, 3, 4, "LLM", "user", 5L)));
        verifyNoInteractions(crypto);
    }

    @Test void referencedRelayCannotBeDeleted()
    {
        when(mapper.lockAccount(1)).thenReturn(1);
        Map<String, Object> row = new HashMap<>();
        row.put("relayId", 8L); row.put("authEpoch", 2L); row.put("accessSecretId", 9L);
        when(mapper.lockService(1, 8)).thenReturn(row);
        when(mapper.countReferences(8)).thenReturn(1);
        assertThrows(ServiceException.class, () -> service.delete(1, 8, "2", "request-1"));
        verify(mapper, never()).setStatus(1, 8, "DELETED", 2);
        verify(mapper, never()).disableSecret(1, 9);
    }

    @Test void sameKeyDeleteReplaysWithoutRepeatingMutation() throws Exception
    {
        when(mapper.lockAccount(1)).thenReturn(1);
        String scope = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest("relay:delete:8".getBytes(StandardCharsets.UTF_8)));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest("delete|8|2".getBytes(StandardCharsets.UTF_8));
        when(mapper.idempotency(1, scope, "request-1")).thenReturn(Map.of(
            "requestHash", hash, "resourceId", 8L, "status", "SUCCEEDED"));
        when(mapper.serviceAnyStatus(1, 8)).thenReturn(Map.of("relayId", 8L, "accountId", 1L, "status", "DELETED"));
        service.delete(1, 8, "2", "request-1");
        verify(mapper, never()).lockService(1, 8);
        verify(mapper, never()).setStatus(1, 8, "DELETED", 2);
    }
}
