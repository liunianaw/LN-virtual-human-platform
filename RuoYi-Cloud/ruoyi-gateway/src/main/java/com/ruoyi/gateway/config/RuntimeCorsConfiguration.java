package com.ruoyi.gateway.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** Only browser runtime HTTP endpoints accept explicitly configured page origins. */
@Configuration
public class RuntimeCorsConfiguration
{
    @Bean
    CorsWebFilter runtimeCors()
    {
        String configured = System.getenv("LN_RUNTIME_ALLOWED_ORIGINS");
        CorsConfiguration cors = new CorsConfiguration();
        if (configured != null && !configured.isBlank())
        {
            for (String item : configured.split(","))
            {
                String origin = item.trim();
                if (!origin.matches("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?")
                    && !origin.matches("http://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]{1,5})?"))
                    throw new IllegalArgumentException("LN_RUNTIME_ALLOWED_ORIGINS requires explicit HTTPS origins");
                cors.addAllowedOrigin(origin);
            }
        }
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Connection-Epoch"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/runtime/**", cors);
        return new CorsWebFilter(source);
    }
}
