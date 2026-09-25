package com.ruoyi.system.relay.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.exception.ServiceException;

class RelayTargetTest
{
    private final RelayTarget target = new RelayTarget();

    @Test void rejectsUnsafeNetworkTargetsAndEncodedPaths() throws Exception
    {
        for (String value : new String[] { "http://example.com", "https://localhost",
            "https://user@example.com", "https://example.com:8443", "https://example.com/%2e%2e/admin",
            "https://example.com/path?next=http://localhost", "https://example.com/#fragment" })
            assertThrows(ServiceException.class, () -> target.validate(value), value);
        assertFalse(RelayTarget.publicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(RelayTarget.publicAddress(InetAddress.getByName("169.254.169.254")));
        assertFalse(RelayTarget.publicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(RelayTarget.publicAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(RelayTarget.publicAddress(InetAddress.getByName("8.8.8.8")));
    }
}
