package com.cy.conversation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话指标采集器
 * <p>
 * 提供对话相关的可观测性指标采集，包括：
 * - 对话次数
 * - Token消耗
 * - 首帧响应时间
 * - 命名成功率
 * - 历史查询耗时
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
public class ConversationMetrics {

    private static final Logger log = LoggerFactory.getLogger(ConversationMetrics.class);

    /**
     * 指标前缀
     */
    private static final String METRIC_PREFIX = "conversation.";

    private final MeterRegistry registry;

    /**
     * 是否启用指标采集
     */
    private final boolean enabled;

    /**
     * 对话次数计数器
     */
    private Counter chatCounter;

    /**
     * Token消耗计数器
     */
    private Counter tokenCounter;

    /**
     * 首帧响应时间计时器
     */
    private Timer firstTokenTimer;

    /**
     * 总响应时间计时器
     */
    private Timer totalDurationTimer;

    /**
     * 命名成功计数器
     */
    private Counter namingSuccessCounter;

    /**
     * 命名失败计数器
     */
    private Counter namingFailureCounter;

    /**
     * 历史查询计时器
     */
    private Timer historyQueryTimer;

    /**
     * 活跃会话数
     */
    private final AtomicInteger activeSessionCount;

    /**
     * 无参构造函数（空操作模式）
     */
    public ConversationMetrics() {
        this.registry = null;
        this.enabled = false;
        this.activeSessionCount = new AtomicInteger(0);
        log.info("会话指标采集器初始化完成（空操作模式，未配置MeterRegistry）");
    }

    /**
     * 构造函数
     *
     * @param registry Micrometer指标注册器
     */
    public ConversationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.enabled = true;
        this.activeSessionCount = new AtomicInteger(0);

        // 初始化对话次数计数器
        this.chatCounter = Counter.builder(METRIC_PREFIX + "chat.count")
                .description("对话总次数")
                .register(registry);

        // 初始化Token消耗计数器
        this.tokenCounter = Counter.builder(METRIC_PREFIX + "chat.tokens")
                .description("Token消耗总量")
                .register(registry);

        // 初始化首帧响应时间计时器
        this.firstTokenTimer = Timer.builder(METRIC_PREFIX + "chat.first_token_latency")
                .description("首帧响应时间")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(registry);

        // 初始化总响应时间计时器
        this.totalDurationTimer = Timer.builder(METRIC_PREFIX + "chat.total_duration")
                .description("总响应时间")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(registry);

        // 初始化命名计数器
        this.namingSuccessCounter = Counter.builder(METRIC_PREFIX + "naming.success")
                .description("命名成功次数")
                .register(registry);

        this.namingFailureCounter = Counter.builder(METRIC_PREFIX + "naming.failure")
                .description("命名失败次数")
                .register(registry);

        // 初始化历史查询计时器
        this.historyQueryTimer = Timer.builder(METRIC_PREFIX + "message.query_latency")
                .description("历史消息查询耗时")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(registry);

        // 注册活跃会话数
        registry.gauge(METRIC_PREFIX + "session.active_count", activeSessionCount);

        log.info("会话指标采集器初始化完成");
    }

    /**
     * 记录对话指标
     *
     * @param model        使用的模型
     * @param tokens       Token消耗数
     * @param firstTokenMs 首帧响应耗时（毫秒）
     * @param durationMs   总耗时（毫秒）
     */
    public void recordChat(String model, int tokens, long firstTokenMs, long durationMs) {
        if (!enabled) {
            return;
        }

        // 记录对话次数
        chatCounter.increment();

        // 记录Token消耗
        tokenCounter.increment(tokens);

        // 记录首帧响应时间
        firstTokenTimer.record(firstTokenMs, TimeUnit.MILLISECONDS);

        // 记录总响应时间
        totalDurationTimer.record(durationMs, TimeUnit.MILLISECONDS);

        // 按模型记录额外指标
        Counter.builder(METRIC_PREFIX + "chat.count.by_model")
                .description("按模型统计的对话次数")
                .tag("model", model)
                .register(registry)
                .increment();

        Counter.builder(METRIC_PREFIX + "chat.tokens.by_model")
                .description("按模型统计的Token消耗")
                .tag("model", model)
                .register(registry)
                .increment(tokens);

        log.debug("记录对话指标: 模型={}, Token数={}, 首帧={}ms, 总耗时={}ms",
                model, tokens, firstTokenMs, durationMs);
    }

    /**
     * 记录命名结果
     *
     * @param success 是否成功
     */
    public void recordNamingResult(boolean success) {
        if (!enabled) {
            return;
        }

        if (success) {
            namingSuccessCounter.increment();
        } else {
            namingFailureCounter.increment();
        }

        log.debug("记录命名结果: {}", success ? "成功" : "失败");
    }

    /**
     * 记录历史查询耗时
     *
     * @param durationMs 查询耗时（毫秒）
     */
    public void recordHistoryQuery(long durationMs) {
        if (!enabled) {
            return;
        }

        historyQueryTimer.record(durationMs, TimeUnit.MILLISECONDS);

        log.debug("记录历史查询耗时: {}ms", durationMs);
    }

    /**
     * 增加活跃会话数
     */
    public void incrementActiveSession() {
        activeSessionCount.incrementAndGet();
    }

    /**
     * 减少活跃会话数
     */
    public void decrementActiveSession() {
        activeSessionCount.decrementAndGet();
    }

    /**
     * 获取当前对话总次数
     *
     * @return 对话次数
     */
    public double getChatCount() {
        return enabled ? chatCounter.count() : 0;
    }

    /**
     * 获取当前Token消耗总量
     *
     * @return Token总量
     */
    public double getTokenCount() {
        return enabled ? tokenCounter.count() : 0;
    }

    /**
     * 获取命名成功率
     *
     * @return 成功率（0.0-1.0）
     */
    public double getNamingSuccessRate() {
        if (!enabled) {
            return 1.0;
        }
        double success = namingSuccessCounter.count();
        double failure = namingFailureCounter.count();
        double total = success + failure;
        return total > 0 ? success / total : 1.0;
    }
}
