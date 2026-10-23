package com.cy.auth.config;

import com.cy.auth.interceptor.AuthInterceptor;
import com.cy.auth.support.AnonymousPathContributor;
import com.cy.auth.support.AnonymousPathMatcher;
import com.cy.auth.support.DefaultAnonymousPathPatterns;
import com.cy.auth.support.PatternAnonymousPathMatcher;
import com.cy.auth.annotation.AnonymousAccess;
import com.cy.web.exception.ServletErrorResponseWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Servlet 环境下的认证拦截器配置。
 * <p>
 * {@code @ConditionalOnClass(name = "...")} 配合 {@code @AutoConfiguration} 使用，
 * Spring Boot 会在加载此类之前通过字节码元数据检查条件，
 * 在 Reactive 环境（无 DispatcherServlet）下直接跳过，不会触发 ClassNotFoundException。
 * </p>
 */
@AutoConfiguration(after = AuthAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthServletAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AnonymousPathMatcher.class)
    public AnonymousPathMatcher anonymousPathMatcher(AuthProperties authProperties,
                                                     ApplicationContext applicationContext,
                                                     ObjectProvider<AnonymousPathContributor> contributorProvider) {
        return new PatternAnonymousPathMatcher(() -> collectAnonymousPathPatterns(authProperties, applicationContext, contributorProvider));
    }

    @Bean
    public WebMvcConfigurer authWebMvcConfigurer(AuthProperties authProperties,
                                                  AnonymousPathMatcher anonymousPathMatcher,
                                                  ServletErrorResponseWriter errorResponseWriter) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AuthInterceptor(authProperties, anonymousPathMatcher, errorResponseWriter))
                        .addPathPatterns("/**")
                        .order(0);
            }
        };
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
        for (Map.Entry<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            if (handlerMethod.hasMethodAnnotation(AnonymousAccess.class)
                    || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), AnonymousAccess.class)) {
                patterns.addAll(entry.getKey().getPatternValues());
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
