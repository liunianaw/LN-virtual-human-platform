package com.ruoyi.common.log.aspect;

import java.util.Map;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.system.api.domain.SysOperLog;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogPrivacyTest
{
    @Log(title = "配置修改")
    public void update() {}

    @Test
    void normalAuditKeepsActionButDoesNotCollectBodies() throws Exception
    {
        var request = new MockHttpServletRequest("POST", "/config");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try
        {
            JoinPoint point = mock(JoinPoint.class);
            when(point.getArgs()).thenReturn(new Object[]{Map.of("providerKey", "private-value")});
            var audit = new SysOperLog();
            new LogAspect().getControllerMethodDescription(point,
                getClass().getMethod("update").getAnnotation(Log.class), audit, Map.of("text", "private-response"));
            assertEquals("配置修改", audit.getTitle());
            assertNull(audit.getOperParam());
            assertNull(audit.getJsonResult());
        }
        finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
