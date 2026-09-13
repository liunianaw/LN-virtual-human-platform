package com.ruoyi.session.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

@Configuration
@EnableConfigurationProperties(VoiceRuntimeProperties.class)
public class RuntimeVoiceConfiguration
{
    /** Fail closed until a protected Console DEBUG grant/JTI verifier is supplied. */
    @Bean
    @ConditionalOnMissingBean(ConsoleDebugSessionAuthenticator.class)
    ConsoleDebugSessionAuthenticator unavailableConsoleDebugSessionAuthenticator()
    {
        return authorizationHeader -> {
            throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "DEBUG_AUTH_NOT_CONFIGURED",
                    "Console DEBUG Session authentication is not configured.");
        };
    }
}
