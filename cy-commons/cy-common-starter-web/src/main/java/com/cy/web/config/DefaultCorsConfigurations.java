package com.cy.web.config;

import org.springframework.web.cors.CorsConfiguration;

public final class DefaultCorsConfigurations {

    private DefaultCorsConfigurations() {
    }

    public static CorsConfiguration create() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOrigin(CorsConfiguration.ALL);
        configuration.addAllowedMethod(CorsConfiguration.ALL);
        configuration.addAllowedHeader(CorsConfiguration.ALL);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        return configuration;
    }
}
