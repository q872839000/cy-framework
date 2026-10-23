package com.cy.mq.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * MQ 线程池执行器工厂。
 * <p>
 * 统一负责将 {@link MqProperties.ThreadPool} 配置转换为 Spring 管理的
 * {@link ThreadPoolTaskExecutor}，确保 Local / Redis / Kafka 三类执行器
 * 在容量限制、拒绝策略与关闭行为上保持一致。
 * </p>
 *
 * @author pcy
 * @date 2026/04/15
 */
public class MqExecutorFactory {

    /**
     * 创建线程池执行器。
     *
     * @param threadNamePrefix 线程名前缀
     * @param properties       线程池配置
     * @return Spring 管理的线程池执行器
     */
    public ThreadPoolTaskExecutor createExecutor(String threadNamePrefix, MqProperties.ThreadPool properties) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix must not be null");
        Objects.requireNonNull(properties, "properties must not be null");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(normalizeCorePoolSize(properties.getCorePoolSize()));
        executor.setMaxPoolSize(normalizeMaxPoolSize(properties.getCorePoolSize(), properties.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(0, properties.getQueueCapacity()));
        executor.setKeepAliveSeconds(toSeconds(properties.getKeepAlive()));
        executor.setAllowCoreThreadTimeOut(properties.isAllowCoreThreadTimeOut());
        executor.setWaitForTasksToCompleteOnShutdown(properties.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(toSeconds(properties.getAwaitTermination()));
        executor.setRejectedExecutionHandler(resolveRejectedExecutionHandler(properties.getRejectionPolicy()));
        executor.initialize();
        return executor;
    }

    /**
     * 解析拒绝策略。
     *
     * @param rejectionPolicy 拒绝策略配置
     * @return JDK 拒绝策略处理器
     */
    private RejectedExecutionHandler resolveRejectedExecutionHandler(MqProperties.RejectionPolicy rejectionPolicy) {
        MqProperties.RejectionPolicy policy = rejectionPolicy == null
                ? MqProperties.RejectionPolicy.ABORT
                : rejectionPolicy;
        return switch (policy) {
            case CALLER_RUNS -> new ThreadPoolExecutor.CallerRunsPolicy();
            case DISCARD -> new ThreadPoolExecutor.DiscardPolicy();
            case DISCARD_OLDEST -> new ThreadPoolExecutor.DiscardOldestPolicy();
            case ABORT -> new ThreadPoolExecutor.AbortPolicy();
        };
    }

    /**
     * 规范化核心线程数。
     */
    private int normalizeCorePoolSize(int corePoolSize) {
        return Math.max(1, corePoolSize);
    }

    /**
     * 规范化最大线程数。
     */
    private int normalizeMaxPoolSize(int corePoolSize, int maxPoolSize) {
        return Math.max(normalizeCorePoolSize(corePoolSize), maxPoolSize);
    }

    /**
     * 将持续时间转换为秒。
     */
    private int toSeconds(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return 0;
        }
        long seconds = duration.getSeconds();
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }
}
