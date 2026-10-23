package com.cy.web.config;

import com.cy.web.context.ReactorInvocationContextBridge;
import com.cy.web.context.ServletReactiveInvocationContextAdvice;
import com.cy.web.filter.InvocationContextFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Servlet 环境自动配置（CORS + 调用上下文 + Servlet/Reactor 桥接）。
 * <p>
 * 通过 {@link CorsFilter}（Servlet Filter 级别）在认证拦截器之前处理 CORS，
 * 确保 OPTIONS 预检请求能正确获得 CORS 响应头，不会被 {@code AuthInterceptor} 拦截。
 * 此设计与 Reactive 侧的 {@link com.cy.web.filter.OrderedCorsWebFilter}（{@code HIGHEST_PRECEDENCE}）对齐。
 * </p>
 * <p>
 * CORS 策略统一由 {@link DefaultCorsConfigurations} 定义，Servlet 和 Reactive 共用同一份配置。
 * 服务可声明自定义 {@link CorsFilter} Bean 覆盖默认策略。
 * </p>
 * <p>
 * 当 Reactor 在 classpath 上时，额外注册：
 * <ul>
 *   <li>{@link com.cy.web.context.ReactorInvocationContextBridge} — Reactor 与 ThreadLocal 的双向上下文桥接</li>
 *   <li>{@link com.cy.web.context.ServletReactiveInvocationContextAdvice} — MVC 返回 {@code Mono}/{@code Flux} 时自动注入上下文</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebServletAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "invocationContextFilterRegistration")
    public FilterRegistrationBean<InvocationContextFilter> invocationContextFilterRegistration() {
        FilterRegistrationBean<InvocationContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InvocationContextFilter());
        registration.addUrlPatterns("/*");
        registration.setName("invocationContextFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(name = "corsFilterRegistration")
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", DefaultCorsConfigurations.create());
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean
    @ConditionalOnClass(name = "reactor.core.publisher.Hooks")
    @ConditionalOnMissingBean
    public ReactorInvocationContextBridge reactorInvocationContextBridge() {
        return new ReactorInvocationContextBridge();
    }

    @Bean
    @ConditionalOnClass(name = "org.reactivestreams.Publisher")
    @ConditionalOnMissingBean(ServletReactiveInvocationContextAdvice.class)
    public ServletReactiveInvocationContextAdvice servletReactiveInvocationContextAdvice() {
        return new ServletReactiveInvocationContextAdvice();
    }
}
