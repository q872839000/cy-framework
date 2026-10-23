package com.cy.web.config;

import com.cy.core.exception.MessageResolver;
import com.cy.web.exception.ExceptionMapperSupport;
import com.cy.web.exception.ReactiveErrorResponseWriter;
import com.cy.web.exception.ReactiveGlobalExceptionHandler;
import com.cy.web.exception.ReactiveGlobalWebExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Reactive 环境全局异常处理自动配置。
 * <p>
 * 注册 {@link ExceptionMapperSupport}（共享映射内核）和 {@link ReactiveGlobalExceptionHandler}。
 * 与 Servlet 环境的 {@link WebExceptionAutoConfiguration} 共用同一个映射内核类，
 * 保证两种运行时对外输出格式完全一致。
 * </p>
 *
 * @author pcy
 * @date 2026/03/24
 */
@AutoConfiguration(after = WebReactiveAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class WebReactiveExceptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExceptionMapperSupport exceptionMapperSupport(MessageResolver messageResolver) {
        return new ExceptionMapperSupport(messageResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveErrorResponseWriter reactiveErrorResponseWriter(ExceptionMapperSupport exceptionMapperSupport) {
        return new ReactiveErrorResponseWriter(exceptionMapperSupport);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveGlobalWebExceptionHandler reactiveGlobalWebExceptionHandler(
            ExceptionMapperSupport exceptionMapperSupport,
            ReactiveErrorResponseWriter reactiveErrorResponseWriter) {
        return new ReactiveGlobalWebExceptionHandler(exceptionMapperSupport, reactiveErrorResponseWriter);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveGlobalExceptionHandler reactiveGlobalExceptionHandler(ExceptionMapperSupport exceptionMapperSupport) {
        return new ReactiveGlobalExceptionHandler(exceptionMapperSupport);
    }
}
