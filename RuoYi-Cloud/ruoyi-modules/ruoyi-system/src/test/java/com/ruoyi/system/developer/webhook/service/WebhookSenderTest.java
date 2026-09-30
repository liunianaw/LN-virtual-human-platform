package com.ruoyi.system.developer.webhook.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.exception.ServiceException;

class WebhookSenderTest
{
    @Test
    void signsTheExactBodyAndRejectsLocalTargets() throws Exception
    {
        String secret = "whsec_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
        assertEquals("v1,rk1DlKTu9EJJUo0AUIPZf4VMYVBc/IYHfWeYZa8IK8U=",
            WebhookSender.signature(secret, "evt_test1", "1700000000",
                "{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ServiceException.class, () -> new WebhookSender().validate("https://127.0.0.1/callback"));
        assertThrows(ServiceException.class, () -> new WebhookSender().validate("https://localhost/callback"));
    }
}
