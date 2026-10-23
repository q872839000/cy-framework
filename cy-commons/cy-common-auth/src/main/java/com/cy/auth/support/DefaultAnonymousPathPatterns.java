package com.cy.auth.support;

import java.util.LinkedHashSet;
import java.util.Set;

public final class DefaultAnonymousPathPatterns {

    private DefaultAnonymousPathPatterns() {
    }

    public static Set<String> create() {
        return new LinkedHashSet<>(Set.of(
                "/",
                "/error",
                "/favicon.ico",
                "/index.html",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/webjars/**",
                "/**/*.html",
                "/**/*.css",
                "/**/*.js",
                "/**/*.ico",
                "/**/*.png",
                "/**/*.svg",
                "/**/*.woff",
                "/**/*.woff2"
        ));
    }
}
