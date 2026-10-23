package com.cy.mq.config;

import com.cy.mq.core.MqDeliveryMode;
import com.cy.mq.core.MqDriverType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 消息队列组件配置属性。
 *
 * @author pcy
 * @date 2026/01/28
 */
@Data
@ConfigurationProperties(prefix = "cy.mq")
public class MqProperties {

    /**
     * 是否启用消息队列组件。
     */
    private boolean enabled = true;

    /**
     * 全局默认驱动。
     * <p>
     * 为空时表示当前应用不声明默认驱动；此时所有依赖默认驱动的发布、订阅与注解声明，
     * 都必须显式指定 {@code driver}，否则会在启动期或运行期快速失败。
     * </p>
     */
    private MqDriverType driver;

    /**
     * 显式启用的驱动集合。
     * <p>
     * 为空时表示启用所有已发现的驱动。
     * </p>
     */
    private Set<MqDriverType> enabledDrivers = new LinkedHashSet<>();

    /**
     * 启动期校验配置。
     */
    private Validation validation = new Validation();

    /**
     * 线程池执行器配置。
     */
    private Executors executors = new Executors();

    /**
     * Kafka 配置。
     */
    private Kafka kafka = new Kafka();

    /**
     * Redis 配置。
     */
    private Redis redis = new Redis();

    /**
     * 本地驱动配置。
     */
    private Local local = new Local();

    /**
     * 重试策略配置。
     */
    private Retry retry = new Retry();

    /**
     * SpEL 表达式解析失败时是否快速失败。
     * <p>
     * 默认为 {@code true}（严格模式），解析失败时抛出异常，避免脏数据进入 MQ。
     * 设置为 {@code false} 时，解析失败会记录警告日志并使用 "null" 替代。
     * </p>
     */
    private boolean spelFailFast = true;

    /**
     * 是否已显式配置默认驱动。
     *
     * @return 是否存在默认驱动
     */
    public boolean hasDefaultDriver() {
        return driver != null;
    }

    /**
     * 判断指定驱动是否被当前应用允许使用。
     *
     * @param driverType 驱动类型
     * @return 是否启用
     */
    public boolean isDriverEnabled(MqDriverType driverType) {
        return driverType != null && (enabledDrivers == null || enabledDrivers.isEmpty() || enabledDrivers.contains(driverType));
    }

    /**
     * 获取生效的启用驱动集合。
     *
     * @return 启用驱动集合快照
     */
    public Set<MqDriverType> getResolvedEnabledDrivers() {
        return enabledDrivers == null ? Set.of() : Set.copyOf(enabledDrivers);
    }

    /**
     * Kafka 配置。
     * <p>
     * 连接参数依旧使用标准的 {@code spring.kafka.*} 配置，此处仅保留组件自身参数。
     * </p>
     */
    @Data
    public static class Kafka {

        /**
         * 默认消费组。
         */
        private String defaultGroupId = "cy-mq";

        /**
         * 消费轮询超时。
         */
        private Duration pollTimeout = Duration.ofSeconds(1);

        /**
         * 发布超时时间。
         */
        private Duration publishTimeout = Duration.ofSeconds(10);

        /**
         * 批量消费时是否跳过坏消息（反序列化失败的消息）。
         * <p>
         * {@code true}: 跳过坏消息并提交位点（消息会丢失）<br>
         * {@code false}: 抛出异常，不提交位点（消息会阻塞消费）
         * </p>
         */
        private boolean skipBadMessages = false;
    }

    /**
     * Redis 配置。
     */
    @Data
    public static class Redis {

        /**
         * Redis 消费者名称中 8 位 UUID 后缀的长度，避免主机名重复时冲突。
         */
        private static final int CONSUMER_NAME_UUID_SUFFIX_LENGTH = 8;

        /**
         * 主机名解析失败后的默认消费者名前缀。
         */
        private static final String FALLBACK_CONSUMER_NAME_PREFIX = "consumer-";

        /**
         * 默认消费组。
         */
        private String defaultGroupId = "cy-mq";

        /**
         * 消费者名称。
         */
        private String consumerName = generateConsumerName();

        /**
         * 消费轮询超时。
         */
        private Duration pollTimeout = Duration.ofSeconds(2);

        /**
         * 每次拉取的消息数量。
         */
        private int batchSize = 10;

        /**
         * Redis Stream pending 消息扫描时每次认领的最大数量。
         */
        private int pendingClaimBatchSize = 10;

        /**
         * Redis 默认投递模式。
         */
        private MqDeliveryMode defaultDeliveryMode = MqDeliveryMode.BOTH;

        /**
         * 按 {@code <hostname>-<uuid8>} 格式生成默认消费者名；
         * 主机名解析失败时回落到 {@link #FALLBACK_CONSUMER_NAME_PREFIX} 前缀。
         */
        private static String generateConsumerName() {
            String suffix = uuidSuffix();
            try {
                String hostname = java.net.InetAddress.getLocalHost().getHostName();
                return hostname + "-" + suffix;
            } catch (Exception e) {
                return FALLBACK_CONSUMER_NAME_PREFIX + suffix;
            }
        }

        /**
         * 获取 UUID 去掉连字符后的前 {@link #CONSUMER_NAME_UUID_SUFFIX_LENGTH} 位。
         */
        private static String uuidSuffix() {
            return java.util.UUID.randomUUID().toString().replace("-", "")
                    .substring(0, CONSUMER_NAME_UUID_SUFFIX_LENGTH);
        }
    }

    /**
     * 本地驱动配置。
     */
    @Data
    public static class Local {

        /**
         * 是否异步投递。
         */
        private boolean async = true;
    }

    /**
     * 重试策略配置。
     */
    @Data
    public static class Retry {

        /**
         * 最大重试次数（-1 表示无限重试）。
         */
        private int maxRetries = 3;

        /**
         * 初始退避时间。
         */
        private Duration initialBackoff = Duration.ofSeconds(1);

        /**
         * 最大退避时间。
         */
        private Duration maxBackoff = Duration.ofMinutes(1);

        /**
         * Redis Stream 消费失败时的最大退避时间。
         */
        private Duration streamMaxBackoff = Duration.ofSeconds(60);

        /**
         * 退避倍数（指数退避）。
         */
        private double backoffMultiplier = 2.0;

        /**
         * 死信队列主题后缀。
         */
        private String dlqTopicSuffix = ".DLQ";

        /**
         * 是否启用死信队列。
         */
        private boolean dlqEnabled = true;

        /**
         * 当 DLQ 启用但写入 DLQ 失败时，是否仍然提交 / ACK 原消息。
         * <p>
         * 默认 {@code false}（安全档位）：DLQ 失败时保留原消息——
         * Kafka 不 commit 让 partition 在下一轮重新投递，
         * Redis Stream 不 ACK 让消息留在 PEL 等待 reclaim。
         * 这会以「partition 阻塞 / PEL 堆积」换取「不丢消息」，由运维介入排查 DLQ。
         * </p>
         * <p>
         * 设置为 {@code true} 时，DLQ 失败也会提交原消息——会丢失该消息但不阻塞通道，
         * 仅在能容忍单条丢失、严禁阻塞的场景下使用。
         * </p>
         */
        private boolean commitOnDlqFailure = false;

        /**
         * Redis Stream pending 消息认领超时时间（毫秒）。
         * <p>
         * 消息在 PEL 中超过此时间未 ACK，会被其他消费者认领重新消费。
         * </p>
         */
        private Duration pendingTimeout = Duration.ofMinutes(5);

        /**
         * Redis Stream pending 消息扫描间隔。
         */
        private Duration pendingScanInterval = Duration.ofSeconds(30);
    }

    /**
     * 启动期校验配置。
     */
    @Data
    public static class Validation {

        /**
         * 当默认驱动或显式驱动引用缺失时是否快速失败。
         */
        private boolean failFastOnMissingDriver = true;
    }

    /**
     * MQ 线程池集合配置。
     */
    @Data
    public static class Executors {

        /**
         * Local 驱动异步投递执行器。
         */
        private ThreadPool local = ThreadPool.localDefaults();

        /**
         * Redis 驱动消费执行器。
         */
        private ThreadPool redis = ThreadPool.consumerDefaults();

        /**
         * Kafka 驱动消费执行器。
         */
        private ThreadPool kafka = ThreadPool.consumerDefaults();
    }

    /**
     * MQ 线程池配置。
     */
    @Data
    public static class ThreadPool {

        /**
         * 核心线程数。
         */
        private int corePoolSize = 2;

        /**
         * 最大线程数。
         */
        private int maxPoolSize = 8;

        /**
         * 队列容量。
         * <p>
         * 对于 Redis/Kafka 这类长生命周期消费任务，建议设置为 {@code 0}，
         * 以便线程池优先扩容而不是无限排队。
         * </p>
         */
        private int queueCapacity = 0;

        /**
         * 非核心线程空闲保活时间。
         */
        private Duration keepAlive = Duration.ofSeconds(60);

        /**
         * 是否允许核心线程超时回收。
         */
        private boolean allowCoreThreadTimeOut;

        /**
         * 关闭容器时是否等待任务执行完成。
         */
        private boolean waitForTasksToCompleteOnShutdown = true;

        /**
         * 关闭容器时的最大等待时间。
         */
        private Duration awaitTermination = Duration.ofSeconds(30);

        /**
         * 队列满时的拒绝策略。
         */
        private RejectionPolicy rejectionPolicy = RejectionPolicy.ABORT;

        /**
         * 构造 Local 驱动异步投递的默认线程池配置。
         *
         * @return Local 默认线程池配置
         */
        public static ThreadPool localDefaults() {
            ThreadPool threadPool = new ThreadPool();
            threadPool.setQueueCapacity(1000);
            threadPool.setRejectionPolicy(RejectionPolicy.CALLER_RUNS);
            return threadPool;
        }

        /**
         * 构造 Redis/Kafka 消费线程池默认配置。
         *
         * @return 消费线程池默认配置
         */
        public static ThreadPool consumerDefaults() {
            ThreadPool threadPool = new ThreadPool();
            threadPool.setQueueCapacity(0);
            threadPool.setRejectionPolicy(RejectionPolicy.ABORT);
            return threadPool;
        }
    }

    /**
     * 线程池拒绝策略。
     */
    public enum RejectionPolicy {

        /** 直接抛出异常。 */
        ABORT,

        /** 由调用线程执行。 */
        CALLER_RUNS,

        /** 静默丢弃当前任务。 */
        DISCARD,

        /** 丢弃最旧任务后重试提交当前任务。 */
        DISCARD_OLDEST
    }
}
