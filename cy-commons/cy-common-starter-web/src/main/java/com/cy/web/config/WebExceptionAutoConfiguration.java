package com.cy.web.config;

import com.cy.core.exception.MessageResolver;
import com.cy.web.exception.ExceptionMapperSupport;
import com.cy.web.exception.GlobalExceptionHandler;
import com.cy.web.exception.ServletErrorResponseWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Servlet 环境全局异常处理自动配置。
 * <p>
 * 注册 {@link ExceptionMapperSupport}（共享映射内核）和 {@link GlobalExceptionHandler}。
 * 服务可声明自定义 Bean 覆盖默认行为。
 * </p>
 */
@AutoConfiguration(after = WebServletAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebExceptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExceptionMapperSupport exceptionMapperSupport(MessageResolver messageResolver) {
        return new ExceptionMapperSupport(messageResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public ServletErrorResponseWriter servletErrorResponseWriter(ExceptionMapperSupport exceptionMapperSupport) {
        return new ServletErrorResponseWriter(exceptionMapperSupport);
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler(ExceptionMapperSupport exceptionMapperSupport) {
        return new GlobalExceptionHandler(exceptionMapperSupport);
    }
}
