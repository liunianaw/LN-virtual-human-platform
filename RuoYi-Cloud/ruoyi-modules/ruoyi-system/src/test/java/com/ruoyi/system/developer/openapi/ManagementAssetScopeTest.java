package com.ruoyi.system.developer.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class ManagementAssetScopeTest
{
    private static final String ROOT = "/openapi/v1/management/";

    @Test
    void everyAssetRouteRequiresItsOwnScope()
    {
        assertEquals("assets:write", scope("POST", "avatar-reference-files"));
        assertEquals("assets:read", scope("GET", "avatar-reference-files/1"));
        assertEquals("generation:write", scope("POST", "avatar-generation-tasks"));
        assertNull(scope("GET", "avatar-generation-tasks/2/production"));
        assertEquals("generation:read", scope("GET", "avatar-generation-tasks/2/steps"));
        assertEquals("generation:read", scope("GET", "avatars/3/versions/4/production"));
        assertEquals("generation:read", scope("GET", "avatar-generation-services"));
        assertEquals("assets:read", scope("GET", "avatars/3/versions/4/preview"));
        assertEquals("generation:write", scope("POST", "avatars/3/versions/4/actions/idle/generations"));
        assertEquals("generation:write", scope("POST", "avatars/3/versions/4/actions/idle/attempts/5/recovery"));
        assertEquals("generation:read", scope("GET", "avatars/3/versions/4/actions/idle/results/6/preview"));
        assertEquals("assets:write", scope("POST", "avatars/3/versions/4/publish"));
        assertEquals("assets:write", scope("DELETE", "avatars/3"));
        assertEquals("config:read", scope("GET", "voices"));
        assertNull(scope("POST", "voices"));
        assertNull(scope("POST", "avatars/3/admin/disable"));
        assertNull(scope("GET", "avatar-generation-tasks/2/attempts/5/provider-secret"));
    }

    private static String scope(String method, String resource)
    { return ManagementKeyFilter.requiredScope(method, ROOT + resource); }
}
