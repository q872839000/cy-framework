package com.cy.log.config;

import com.cy.mq.core.MqDeliveryMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 日志组件配置。
 *
 * @author pcy
 * @date 2026/01/29
 */
@Data
@ConfigurationProperties(prefix = "cy.log")
public class LogProperties {

    /**
     * {@link #maxBodyLength} 的默认值（字符数）。
     * <p>
     * 同时作为 {@code LogRecordNormalizer} 等组件在配置非法时的兜底默认值，
     * 全模块共享同一个常量，确保"配置默认值 == 防御兜底值"始终一致。
     * </p>
     */
    public static final int DEFAULT_MAX_BODY_LENGTH = 2048;

    /**
     * {@link #maxPayloadBytes} 的默认值（字节数）。
     * <p>
     * 同时作为 {@code LogPayloadProcessor}、{@code LogRecordNormalizer}、{@code RequestCachingFilter}
     * 在配置非法时的兜底默认值，全模块共享同一个常量。
     * </p>
     */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024;

    /**
     * 是否启用日志组件。
     */
    private boolean enabled = true;

    /**
     * 审计日志触发请求头名称。
     */
    private String auditHeader = "X-Audit-Required";

    /**
     * 排除的 URL 路径模式列表。
     */
    private List<String> excludePatterns = new ArrayList<>(Arrays.asList(
            "/actuator/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/error"
    ));

    /**
     * 是否记录请求体。
     */
    private boolean recordRequestBody = true;

    /**
     * 是否记录响应体。
     */
    private boolean recordResponseBody = false;

    /**
     * 请求体/响应体最大记录长度。
     * <p>
     * 该参数控制最终落库 / 上报的字符串长度，对所有日志类型统一生效。
     * </p>
     */
    private int maxBodyLength = DEFAULT_MAX_BODY_LENGTH;

    /**
     * 日志组件单条载荷允许采集 / 缓存的最大字节数。
     * <p>
     * 控制以下三层动作：
     * </p>
     * <ul>
     *     <li>请求体缓存：{@code RequestCachingFilter} 仅会缓存前 {@code maxPayloadBytes} 字节</li>
     *     <li>对象序列化：超过该字节数的载荷自动转为预览 + 截断标记</li>
     *     <li>发布前兜底：任意字段超过该字节数都会被强制收敛</li>
     * </ul>
     * <p>
     * 默认 64KB，足以保留可读审计内容，同时防止文件 / 大对象触发 OOM。
     * </p>
     */
    private int maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;

    /**
     * 敏感字段列表。
     */
    private List<String> sensitiveFields = new ArrayList<>(Arrays.asList(
            "password",
            "pwd",
            "secret",
            "token",
            "accessToken",
            "refreshToken",
            "credential"
    ));

    /**
     * TEXT 字段安全截断长度（防止 MySQL TEXT 类型溢出）。
     */
    private int textSafeLimit = 15000;

    /**
     * 拦截器缓存最大容量。
     */
    private int interceptorCacheSize = 100;

    /**
     * ACCESS 日志配置。
     */
    private Access access = new Access();

    /**
     * 发布治理配置。
     */
    private Publish publish = new Publish();

    /**
     * 业务日志 MQ 配置。
     */
    private Mq mq = new Mq();

    /**
     * 持久化配置。
     */
    private Persistence persistence = new Persistence();

    /**
     * 清理配置。
     */
    private Cleanup cleanup = new Cleanup();

    /**
     * ACCESS 日志配置。
     */
    @Data
    public static class Access {

        /**
         * 是否启用 ACCESS 日志。
         */
        private boolean enabled = true;

        /**
         * 是否启用 ACCESS 持久化消费者。
         */
        private boolean persist = true;

        /**
         * ACCESS 采样率。
         */
        private double sampleRate = 0.1D;

        /**
         * ACCESS 详情模式。
         */
        private AccessDetailMode detailMode = AccessDetailMode.ROOT_ONLY;

        /**
         * 详情模式下的附加采样率。
         * <p>
         * 仅在 {@link AccessDetailMode#SAMPLED_SPANS} 模式下生效。
         * </p>
         */
        private double detailSampleRate = 0.1D;

        /**
         * 压力状态下的详情模式。
         */
        private AccessDetailMode pressureDetailMode = AccessDetailMode.ERROR_ONLY;

        /**
         * 紧急状态下的详情模式。
         */
        private AccessDetailMode emergencyDetailMode = AccessDetailMode.ROOT_ONLY;

        /**
         * 压力状态下是否允许记录入参。
         */
        private boolean recordInputUnderPressure = false;

        /**
         * 压力状态下是否允许记录出参。
         */
        private boolean recordOutputUnderPressure = false;

        /**
         * 全局入参字符限制，-1 表示不限制。
         */
        private int defaultReqCharLimit = 2048;

        /**
         * 全局出参字符限制，-1 表示不限制。
         */
        private int defaultRetCharLimit = 2048;

        /**
         * 是否额外写入本地 access.log。
         */
        private boolean enableLogFile = false;

        /**
         * ACCESS 日志 Topic。
         */
        private String topic;

        /**
         * ACCESS 日志 MQ 投递模式。
         */
        private MqDeliveryMode deliveryMode = MqDeliveryMode.STREAM;

        /**
         * ACCESS 持久化消费组。
         */
        private String groupId = "log-access-persistence";

        /**
         * ACCESS 死信 Topic。
         */
        private String deadLetterTopic;

        /**
         * ACCESS 死信回放消费组。
         */
        private String deadLetterReplayGroupId = "log-access-dlq-replay";

        /**
         * ACCESS 死信最终停放 Topic。
         */
        private String deadLetterParkingLotTopic;

        /**
         * ACCESS 表前缀。
         */
        private String tablePrefix = "access_log";

        /**
         * ACCESS 单表最大行数。
         */
        private int maxRowsPerTable = 1_000_000;

        /**
         * ACCESS 日志保留天数。
         */
        private int retentionDays = 30;
    }

    /**
     * 发布治理配置。
     */
    @Data
    public static class Publish {

        /**
         * 是否启用日志生产端发布能力。
         * <p>
         * 关闭后不装配 LogPublisher、发布切面、发布车道线程池及相关上下文桥接。
         * </p>
         */
        private boolean enabled = true;

        /**
         * 业务日志发布车道。
         */
        private Lane business = Lane.businessDefaults();

        /**
         * ACCESS 日志发布车道。
         */
        private Lane access = Lane.accessDefaults();

        /**
         * 熔断配置。
         */
        private Breaker breaker = new Breaker();

        /**
         * 降级配置。
         */
        private Degrade degrade = new Degrade();

        /**
         * 本地兜底配置。
         */
        private Fallback fallback = new Fallback();
    }

    /**
     * 单个发布车道配置。
     */
    @Data
    public static class Lane {

        /**
         * 是否启用该车道。
         */
        private boolean enabled = true;

        /**
         * 核心工作线程数。
         */
        private int coreThreads = 1;

        /**
         * 最大工作线程数。
         */
        private int maxThreads = 1;

        /**
         * 空闲线程保活时长，单位毫秒。
         */
        private long keepAliveMs = 60_000L;

        /**
         * 队列容量。
         */
        private int queueCapacity = 1024;

        /**
         * 队列满时是否尝试本地兜底。
         */
        private boolean fallbackOnReject = false;

        /**
         * 发布失败时是否尝试本地兜底。
         */
        private boolean fallbackOnFailure = true;

        /**
         * 业务日志默认车道配置。
         */
        public static Lane businessDefaults() {
            Lane lane = new Lane();
            lane.setCoreThreads(2);
            lane.setMaxThreads(4);
            lane.setQueueCapacity(2048);
            lane.setFallbackOnReject(true);
            lane.setFallbackOnFailure(true);
            return lane;
        }

        /**
         * ACCESS 日志默认车道配置。
         */
        public static Lane accessDefaults() {
            Lane lane = new Lane();
            lane.setCoreThreads(1);
            lane.setMaxThreads(2);
            lane.setQueueCapacity(8192);
            lane.setFallbackOnReject(false);
            lane.setFallbackOnFailure(false);
            return lane;
        }
    }

    /**
     * 熔断配置。
     */
    @Data
    public static class Breaker {

        /**
         * 滑动窗口长度，单位毫秒。
         */
        private long windowMs = 30_000L;

        /**
         * 熔断判定最少调用次数。
         */
        private int minimumCalls = 20;

        /**
         * 连续失败阈值。
         */
        private int consecutiveFailures = 8;

        /**
         * 失败率阈值，取值范围 0~1。
         */
        private double failureRateThreshold = 0.5D;

        /**
         * 打开熔断后的保持时长，单位毫秒。
         */
        private long openDurationMs = 30_000L;

        /**
         * 半开状态允许的探测请求数。
         */
        private int halfOpenPermits = 5;
    }

    /**
     * 动态降级配置。
     */
    @Data
    public static class Degrade {

        /**
         * 是否启用压力降级。
         */
        private boolean enabled = true;

        /**
         * 进入压力降级的队列使用率阈值。
         */
        private double pressureQueueUsage = 0.70D;

        /**
         * 进入紧急降级的队列使用率阈值。
         */
        private double emergencyQueueUsage = 0.90D;
    }

    /**
     * 本地兜底配置。
     */
    @Data
    public static class Fallback {

        /**
         * 是否启用本地兜底。
         */
        private boolean enabled = false;

        /**
         * 兜底目录。
         */
        private String directory = "logs/cy-log-fallback";

        /**
         * 单文件最大大小，单位字节。
         */
        private long maxFileSizeBytes = 16L * 1024L * 1024L;

        /**
         * 最多保留文件数。
         */
        private int maxFiles = 16;
    }

    /**
     * ACCESS 明细模式。
     */
    public enum AccessDetailMode {

        /**
         * 不输出 ACCESS span 明细。
         */
        NONE,

        /**
         * 仅输出根 span。
         */
        ROOT_ONLY,

        /**
         * 仅输出根 span 与错误 span。
         */
        ERROR_ONLY,

        /**
         * 输出根 span，并对明细 span 做额外采样。
         */
        SAMPLED_SPANS,

        /**
         * 输出全部 span。
         */
        FULL
    }

    /**
     * 业务日志 MQ 配置。
     */
    @Data
    public static class Mq {

        /**
         * 业务日志 Topic。
         */
        private String topic;

        /**
         * 业务日志 MQ 投递模式。
         */
        private MqDeliveryMode deliveryMode = MqDeliveryMode.STREAM;

        /**
         * 业务日志持久化消费组。
         */
        private String groupId = "log-persistence";

        /**
         * 业务日志死信 Topic。
         */
        private String deadLetterTopic;

        /**
         * 业务日志死信回放消费组。
         */
        private String deadLetterReplayGroupId = "log-dlq-replay";

        /**
         * 业务日志死信最终停放 Topic。
         */
        private String deadLetterParkingLotTopic;
    }

    /**
     * 持久化配置。
     */
    @Data
    public static class Persistence {

        /**
         * 是否启用持久化消费者。
         */
        private boolean enabled = false;

        /**
         * 单表最大行数。
         */
        private int maxRowsPerTable = 1_000_000;

        /**
         * 表名前缀。
         */
        private String tablePrefix = "sys_log";

        /**
         * 单次数据库批量写入条数。
         */
        private int batchSize = 100;

        /**
         * 持久化最大重试次数。
         */
        private int maxRetryAttempts = 3;

        /**
         * 重试退避时间，单位毫秒。
         */
        private long retryBackoffMs = 300L;

        /**
         * 持久化失败后是否写入死信。
         */
        private boolean deadLetterEnabled = true;

        /**
         * 是否启用死信自动回放。
         */
        private boolean deadLetterReplayEnabled = true;

        /**
         * 死信自动回放最大尝试次数。
         */
        private int deadLetterReplayMaxAttempts = 6;

        /**
         * 死信自动回放退避时间，单位毫秒。
         */
        private long deadLetterReplayBackoffMs = 5000L;
    }

    /**
     * 自动清理配置。
     */
    @Data
    public static class Cleanup {

        /**
         * 是否启用自动清理。
         */
        private boolean enabled = false;

        /**
         * 业务日志保留天数。
         */
        private int retentionDays = 180;

        /**
         * 清理任务 Cron 表达式。
         */
        private String cron = "0 0 2 * * ?";

        /**
         * 是否启用分布式锁。
         */
        private boolean clusterSafe = true;

        /**
         * 分布式锁 Key。
         */
        private String lockKey = "cy:log:cleanup:lock";

        /**
         * 分布式锁 TTL，单位秒。
         */
        private int lockTtlSeconds = 600;
    }
}
