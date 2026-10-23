package com.cy.auth.config;

import com.cy.auth.filter.ReactiveAuthWebFilter;
import com.cy.auth.support.AnonymousPathContributor;
import com.cy.auth.support.AnonymousPathMatcher;
import com.cy.auth.support.DefaultAnonymousPathPatterns;
import com.cy.auth.support.PatternAnonymousPathMatcher;
import com.cy.auth.annotation.AnonymousAccess;
import com.cy.web.exception.ReactiveErrorResponseWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reactive（WebFlux）环境下的认证过滤器配置。
 * <p>
 * {@code @ConditionalOnClass} 使用字符串形式引用 WebFilter，
 * 确保在 classpath 中无 spring-webflux 时此类不会被加载。
 * </p>
 */
@AutoConfiguration(after = AuthAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class AuthReactiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AnonymousPathMatcher.class)
    public AnonymousPathMatcher anonymousPathMatcher(AuthProperties authProperties,
                                                     ApplicationContext applicationContext,
                                                     ObjectProvider<AnonymousPathContributor> contributorProvider) {
        return new PatternAnonymousPathMatcher(() -> collectAnonymousPathPatterns(authProperties, applicationContext, contributorProvider));
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveAuthWebFilter reactiveAuthWebFilter(AuthProperties authProperties,
                                                        AnonymousPathMatcher anonymousPathMatcher,
                                                        ReactiveErrorResponseWriter errorResponseWriter) {
        return new ReactiveAuthWebFilter(authProperties, anonymousPathMatcher, errorResponseWriter);
    }

    private Set<String> collectAnonymousPathPatterns(AuthProperties authProperties,
                                                     ApplicationContext applicationContext,
                                                     ObjectProvider<AnonymousPathContributor> contributorProvider) {
        Set<String> patterns = new LinkedHashSet<>(DefaultAnonymousPathPatterns.create());
        contributorProvider.orderedStream().forEach(contributor -> patterns.addAll(contributor.getAnonymousPathPatterns()));
        if (authProperties.getWhitelist() != null) {
            patterns.addAll(authProperties.getWhitelist());
        }
        RequestMappingHandlerMapping handlerMapping = getRequestMappingHandlerMapping(applicationContext);
        if (handlerMapping == null) {
            return patterns;
        }
        for (Map.Entry<org.springframework.web.reactive.result.method.RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            if (handlerMethod.hasMethodAnnotation(AnonymousAccess.class)
                    || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), AnonymousAccess.class)) {
                entry.getKey()
                        .getPatternsCondition()
                        .getPatterns()
                        .forEach(pattern -> patterns.add(pattern.toString()));
            }
        }
        return patterns;
    }

    private RequestMappingHandlerMapping getRequestMappingHandlerMapping(ApplicationContext ctx) {
        try {
            return ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        } catch (Exception e) {
            return null;
        }
    }
}
