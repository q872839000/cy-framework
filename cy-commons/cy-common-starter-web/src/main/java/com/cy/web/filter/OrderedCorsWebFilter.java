package com.cy.web.filter;

import org.springframework.core.Ordered;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;

public class OrderedCorsWebFilter extends CorsWebFilter implements Ordered {

    public OrderedCorsWebFilter(CorsConfigurationSource configSource) {
        super(configSource);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
