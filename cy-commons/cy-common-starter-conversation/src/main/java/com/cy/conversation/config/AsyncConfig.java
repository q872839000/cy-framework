package com.cy.conversation.config;

import com.cy.core.context.InvocationContextAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * 异步任务配置类
 * <p>
 * 配置异步任务执行器，用于智能命名等异步操作
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * 配置异步任务执行器
     *
     * @return 线程池执行器
     */
    @Bean(name = "conversationAsyncExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数
        executor.setCorePoolSize(2);
        // 最大线程数
        executor.setMaxPoolSize(10);
        // 队列容量
        executor.setQueueCapacity(100);
        // 线程名前缀
        executor.setThreadNamePrefix("conv-async-");
        // 线程空闲时间
        executor.setKeepAliveSeconds(60);
        // 传播 InvocationContext 到异步线程，确保异步命名等任务能读取到正确的用户上下文
        executor.setTaskDecorator(task -> InvocationContextAccessor.wrap(task));
        // 等待所有任务完成后再关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 最大等待时间
        executor.setAwaitTerminationSeconds(30);
        // 初始化
        executor.initialize();

        log.info("会话异步任务执行器初始化完成");
        return executor;
    }

    /**
     * 异步任务异常处理器
     *
     * @return 异常处理器
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new ConversationAsyncExceptionHandler();
    }

    /**
     * 会话异步任务异常处理器
     */
    private static class ConversationAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("异步任务执行异常，方法: {}.{}", method.getDeclaringClass().getSimpleName(),
                    method.getName(), ex);
        }
    }
}
