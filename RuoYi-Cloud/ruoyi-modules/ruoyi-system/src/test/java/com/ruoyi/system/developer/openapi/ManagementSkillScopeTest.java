package com.ruoyi.system.developer.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class ManagementSkillScopeTest
{
    @Test void skillRoutesUseConfigScopesAndDoNotExposeOfficialWrites()
    {
        String path = "/openapi/v1/management/skills";
        assertEquals("config:read", ManagementKeyFilter.requiredScope("GET", path));
        assertEquals("config:read", ManagementKeyFilter.requiredScope("GET", path + "/candidates"));
        assertEquals("config:write", ManagementKeyFilter.requiredScope("POST", path + "/12/versions"));
        assertEquals("config:write", ManagementKeyFilter.requiredScope("DELETE", path + "/12"));
        assertNull(ManagementKeyFilter.requiredScope("POST", path + "/official"));
        assertNull(ManagementKeyFilter.requiredScope("GET", path + "/12/token"));
    }
}
