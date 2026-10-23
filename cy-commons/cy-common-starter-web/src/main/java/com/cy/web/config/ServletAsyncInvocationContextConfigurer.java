package com.cy.web.config;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 异步请求处理的 {@link InvocationContext} 传播配置。
 * <p>
 * 配置专用的 {@link ThreadPoolTaskExecutor} 并通过
 * {@link InvocationContextAccessor#wrap(Runnable)} 作为 {@code TaskDecorator}，
 * 保证 SSE 流式响应等异步场景中 ORM 监听器和业务服务
 * 能读取到正确的用户上下文。
 * </p>
 * <p>
 * 该配置器可被业务模块继承复用，避免每个模块重复编写相同的异步执行器配置。
 * </p>
 *
 * @author pcy
 * @see InvocationContextAccessor#wrap(Runnable)
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServletAsyncInvocationContextConfigurer implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setKeepAliveSeconds(60);
        executor.setTaskDecorator(InvocationContextAccessor::wrap);
        executor.initialize();

        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(300000);
    }
}
