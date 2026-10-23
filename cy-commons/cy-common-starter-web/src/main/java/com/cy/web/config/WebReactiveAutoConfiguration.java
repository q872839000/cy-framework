package com.cy.web.config;

import com.cy.web.context.ReactorInvocationContextBridge;
import com.cy.web.filter.InvocationContextWebFilter;
import com.cy.web.filter.OrderedCorsWebFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Reactive 环境自动配置（CORS + 调用上下文 + Reactor 桥接）。
 * <p>
 * 通过 {@link OrderedCorsWebFilter}（{@code Ordered.HIGHEST_PRECEDENCE}）在 WebFilter 链最前端处理 CORS，
 * 确保 OPTIONS 预检请求在认证过滤器之前被正确响应。
 * </p>
 * <p>
 * CORS 策略统一由 {@link DefaultCorsConfigurations} 定义，Servlet 和 Reactive 共用同一份配置。
 * 服务可声明自定义 {@link CorsConfigurationSource} Bean 覆盖默认策略。
 * </p>
 * <p>
 * 当 Reactor 在 classpath 上时，额外注册：
 * <ul>
 *   <li>{@link com.cy.web.context.ReactorInvocationContextBridge} — Reactor 与 ThreadLocal 的双向上下文桥接</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class WebReactiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CorsConfigurationSource.class)
    public CorsConfigurationSource cyCommonReactiveCorsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", DefaultCorsConfigurations.create());
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(InvocationContextWebFilter.class)
    public InvocationContextWebFilter invocationContextWebFilter() {
        return new InvocationContextWebFilter();
    }

    @Bean
    @ConditionalOnMissingBean(CorsWebFilter.class)
    public OrderedCorsWebFilter corsWebFilter(CorsConfigurationSource corsConfigurationSource) {
        return new OrderedCorsWebFilter(corsConfigurationSource);
    }

    @Bean
    @ConditionalOnClass(name = "reactor.core.publisher.Hooks")
    @ConditionalOnMissingBean
    public ReactorInvocationContextBridge reactorInvocationContextBridge() {
        return new ReactorInvocationContextBridge();
    }
}
