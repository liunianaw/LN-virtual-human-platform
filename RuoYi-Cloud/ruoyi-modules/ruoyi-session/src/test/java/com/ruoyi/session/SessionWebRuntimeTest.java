package com.ruoyi.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

class SessionWebRuntimeTest {
    @Test
    void applicationUsesServletRuntimeToExposeActuatorHealth() {
        SpringApplication application = new SpringApplication(RuoYiSessionApplication.class);

        assertEquals(WebApplicationType.SERVLET, application.getWebApplicationType());
    }
}
