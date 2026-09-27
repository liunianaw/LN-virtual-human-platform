package com.ruoyi.system.voice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.constant.CacheConstants;
import com.ruoyi.common.redis.service.RedisService;
import com.ruoyi.system.api.model.LoginUser;

class ConsoleLoginRegistryTest
{
    @Test void checksTheCachedSessionKeyAndRejectsRevocation()
    {
        RedisService redis = mock(RedisService.class);
        ConsoleLoginRegistry registry = new ConsoleLoginRegistry(redis);
        LoginUser login = new LoginUser();
        login.setToken("internal-session-key");
        login.setUserid(1L);
        login.setExpireTime(System.currentTimeMillis() + 60000);
        String reference = registry.register(login);
        String cacheKey = CacheConstants.LOGIN_TOKEN_KEY + login.getToken();

        when(redis.<LoginUser>getCacheObject(cacheKey)).thenReturn(login);
        assertTrue(registry.isCurrent(reference, 1));
        assertFalse(registry.isCurrent(reference, 2));

        when(redis.<LoginUser>getCacheObject(cacheKey)).thenReturn(null);
        assertFalse(registry.isCurrent(reference, 1));
    }
}
