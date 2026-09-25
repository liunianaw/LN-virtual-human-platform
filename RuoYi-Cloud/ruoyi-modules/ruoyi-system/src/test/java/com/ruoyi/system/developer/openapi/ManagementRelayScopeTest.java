package com.ruoyi.system.developer.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class ManagementRelayScopeTest
{
    @Test void onlyIntendedRelayRoutesReceiveConfigScopes()
    {
        String base = "/openapi/v1/management/relay-services";
        assertEquals("config:read", ManagementKeyFilter.requiredScope("GET", base));
        assertEquals("config:read", ManagementKeyFilter.requiredScope("GET", base + "/12"));
        assertEquals("config:write", ManagementKeyFilter.requiredScope("POST", base));
        assertEquals("config:write", ManagementKeyFilter.requiredScope("POST", base + "/12/connection-test"));
        assertEquals("config:write", ManagementKeyFilter.requiredScope("PUT", base + "/12/grants"));
        assertEquals("config:write", ManagementKeyFilter.requiredScope("DELETE", base + "/12"));
        assertNull(ManagementKeyFilter.requiredScope("GET", base + "/12/token"));
        assertNull(ManagementKeyFilter.requiredScope("POST", base + "/12/tts"));
    }
}
