package com.cy.mq.driver.redis;

import com.cy.mq.config.MqProperties;
import com.cy.mq.core.ConsumeDecision;
import com.cy.mq.core.ConsumeStatus;
import com.cy.mq.core.MqDeliveryMode;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqHeaders;
import com.cy.mq.core.MqInboundRecord;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqSubscription;
import com.cy.mq.exception.MqPublishException;
import com.cy.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XPendingOptions;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Redis MQ 驱动。
 * <p>
 * 同时支持两种消费语义：
 * </p>
 * <ul>
 *     <li><b>Pub/Sub 广播</b>：未指定 groupId 时使用，best-effort 投递；
 *     受协议限制不携带 framework metadata，body 即 payload。</li>
 *     <li><b>Stream 消费组</b>：指定 groupId 时使用，持久化、可 ACK、支持 pending reclaim；
 *     framework metadata 通过 stream entry 的保留字段（{@link MqHeaders#PREFIX} 前缀）传输，
 *     业务 payload 写入 {@link MqHeaders#PAYLOAD_FIELD} 字段。</li>
 * </ul>
 * <p>
 * <b>字节安全保证：</b>所有 broker IO 都走 {@code byte[]} 级 Redis 命令——
 * Pub/Sub 通过 {@code RedisConnection.publish(byte[], byte[])}，
 * Stream 通过 {@link org.springframework.data.redis.connection.RedisStreamCommands}
 * 的 {@code byte[]} 重载。这样：
 * </p>
 * <ul>
 *     <li>{@code MqOutboundRecord.body} 字节端到端保持不变，支持任意二进制 payload。</li>
 *     <li>header 名 / 值在序列化路径上仅经一次 UTF-8 编解码，与 {@code Map<String, String>} 契约一致。</li>
 *     <li>外部系统直接 {@code XADD topic * payload xxx} 写入的纯文本仍可被消费。</li>
 * </ul>
 * <p>
 * 该 driver 不接触业务 payload 类型，所有反序列化与回调由消费 runtime 处理。
 * </p>
 *
 * @author pcy
 */
@Slf4j
public class RedisMqDriver implements MqDriver {

    /**
     * messageId 不存在时用于 metrics / 日志诊断的占位值。
     */
    private static final String UNKNOWN_MESSAGE_ID = "unknown";

    /**
     * Redis Stream 创建消费组时的起始 offset，{@code "0"} 表示从 stream 头部开始读。
     */
    private static final String STREAM_OFFSET_BEGIN = "0";

    /**
     * Redis Stream 重复创建消费组时的错误标识。
     */
    private static final String REDIS_BUSYGROUP_ERROR = "BUSYGROUP";

    /**
     * Pub/Sub 模式下用于 metrics tag 的 group 占位值。
     */
    private static final String PUBSUB_GROUP_PLACEHOLDER = "pubsub";

    /**
     * 入站记录 attributes 中记录消费模式的 key。
     */
    private static final String ATTR_MODE = "mode";

    /**
     * Pub/Sub 消费模式标识。
     */
    private static final String MODE_PUBSUB = "pubsub";

    /**
     * Stream 消费模式标识。
     */
    private static final String MODE_STREAM = "stream";

    /**
     * Stream 消费循环异常退避的基准间隔（1 秒）。
     */
    private static final long STREAM_BACKOFF_BASE_MS = Duration.ofSeconds(1).toMillis();

    /**
     * Stream 消费循环退避指数位移上限，避免 {@code 1L << n} 溢出。
     */
    private static final int STREAM_BACKOFF_MAX_SHIFT = 16;

    /**
     * Stream payload 字段名预编码字节，热路径复用避免重复编码。
     */
    private static final byte[] PAYLOAD_FIELD_BYTES =
            MqHeaders.PAYLOAD_FIELD.getBytes(StandardCharsets.UTF_8);

    /**
     * DLQ 附加诊断 header：原始 topic。
     */
    private static final String DLQ_HEADER_ORIGINAL_TOPIC = "x-dlq-original-topic";

    /**
     * DLQ 附加诊断 header：原始 record id。
     */
    private static final String DLQ_HEADER_ORIGINAL_RECORD_ID = "x-dlq-original-record-id";

    /**
     * DLQ 附加诊断 header：DLQ 入队时间戳（毫秒）。
     */
    private static final String DLQ_HEADER_TIMESTAMP = "x-dlq-timestamp";

    /**
     * DLQ 附加诊断 header：原始消费异常类名。
     */
    private static final String DLQ_HEADER_ERROR = "x-dlq-error";

    /**
     * Redis 操作模板。仅用于通过 {@link RedisCallback} 拿到原生 {@code RedisConnection}
     * 进而调用 byte[] 级命令；本 driver 不直接调用该模板的 String 序列化 API。
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 消息监听容器。
     */
    private final RedisMessageListenerContainer listenerContainer;

    /**
     * MQ 配置。
     */
    private final MqProperties properties;

    /**
     * Stream 消费执行器。
     */
    private final Executor executor;

    /**
     * 构造函数。
     *
     * @param redisTemplate     Redis 模板（仅用于 RedisCallback 获取连接）
     * @param listenerContainer Pub/Sub 监听容器
     * @param properties        MQ 配置
     * @param executor          Stream 消费执行器
     */
    public RedisMqDriver(StringRedisTemplate redisTemplate,
                         RedisMessageListenerContainer listenerContainer,
                         MqProperties properties,
                         Executor executor) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.listenerContainer = Objects.requireNonNull(listenerContainer, "listenerContainer must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public MqDriverType type() {
        return MqDriverType.REDIS;
    }

    /**
     * DLQ 发送结果。driver 据此决定是否 ACK 原消息。
     */
    private enum DlqOutcome {
        /** DLQ 未启用，调用方明确选择丢弃 → ACK。 */
        DLQ_DISABLED,
        /** DLQ 发送成功 → ACK。 */
        DLQ_SUCCESS,
        /** DLQ 发送失败 → 由 commitOnDlqFailure 决定是否 ACK。 */
        DLQ_FAILED
    }

    // ==================== Publish ====================

    @Override
    public void publish(MqOutboundRecord record) {
        if (record == null || record.getTopic() == null) {
            return;
        }

        String messageId = resolveMessageId(record.safeHeaders());
        MqDeliveryMode deliveryMode = resolveDeliveryMode(record.getDeliveryMode());
        long startTime = System.currentTimeMillis();

        try {
            if (deliveryMode == MqDeliveryMode.PUBSUB || deliveryMode == MqDeliveryMode.BOTH) {
                publishPubSub(record);
            }
            if (deliveryMode == MqDeliveryMode.STREAM || deliveryMode == MqDeliveryMode.BOTH) {
                publishStream(record);
            }
            MqMetrics.recordPublishSuccess(record.getTopic(), type().name(), messageId);
            MqMetrics.recordPublishDuration(record.getTopic(), type().name(), messageId,
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            MqMetrics.recordPublishFailure(record.getTopic(), type().name(), messageId);
            MqMetrics.recordPublishDuration(record.getTopic(), type().name(), messageId,
                    System.currentTimeMillis() - startTime);
            throw new MqPublishException(record.getTopic(), messageId,
                    "Redis MQ 发布失败: topic=" + record.getTopic(), e);
        }
    }

    /**
     * Pub/Sub 发布：以 byte[] 级 publish 命令直发原始 payload，避免 String 转换损坏二进制内容。
     */
    private void publishPubSub(MqOutboundRecord record) {
        byte[] payload = record.getBody() == null ? new byte[0] : record.getBody();
        byte[] channel = record.getTopic().getBytes(StandardCharsets.UTF_8);
        redisTemplate.execute((RedisCallback<Long>) connection -> connection.publish(channel, payload));
    }

    /**
     * Stream 发布：以 byte[] 级 xAdd 命令写入字段映射，
     * payload 字段持有原始字节，header 字段以 UTF-8 编码（与 {@code Map<String,String>} 契约一致）。
     */
    private void publishStream(MqOutboundRecord record) {
        byte[] streamKey = record.getTopic().getBytes(StandardCharsets.UTF_8);
        Map<byte[], byte[]> fields = toStreamFields(record);
        redisTemplate.execute((RedisCallback<RecordId>) connection ->
                connection.streamCommands().xAdd(streamKey, fields));
    }

    /**
     * 把出站记录转换为 byte[] 级 Stream 字段映射。
     * <ul>
     *     <li>业务 / framework headers 使用 UTF-8 编码 name 与 value（headers 契约即 {@code Map<String,String>}）。</li>
     *     <li>{@link MqHeaders#PAYLOAD_FIELD} 字段持有原始 {@code byte[]} payload。</li>
     *     <li>业务方写入 {@code payload} header 会被忽略，避免与保留字段冲突。</li>
     * </ul>
     */
    private Map<byte[], byte[]> toStreamFields(MqOutboundRecord record) {
        Map<byte[], byte[]> fields = new LinkedHashMap<>();
        if (record.getHeaders() != null) {
            for (Map.Entry<String, String> entry : record.getHeaders().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (MqHeaders.PAYLOAD_FIELD.equals(entry.getKey())) {
                    log.warn("Redis Stream 发布忽略 header 名 'payload'（与保留字段冲突）: topic={}", record.getTopic());
                    continue;
                }
                fields.put(entry.getKey().getBytes(StandardCharsets.UTF_8),
                        entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        byte[] payload = record.getBody() == null ? new byte[0] : record.getBody();
        fields.put(PAYLOAD_FIELD_BYTES, payload);
        return fields;
    }

    @Override
    public MqSubscription subscribe(MqSubscribeSpec spec, MqRecordHandler handler) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(spec.getTopic(), "spec.topic must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        String topic = spec.getTopic();
        String groupId = spec.getGroupId();
        if (groupId == null || groupId.isBlank()) {
            return subscribePubSub(topic, handler);
        }
        return subscribeStream(spec, handler);
    }

    /**
     * 解析投递模式。
     * <ul>
     *     <li>显式指定（非 DEFAULT）：直接使用。</li>
     *     <li>DEFAULT 或 null：使用 {@code cy.mq.redis.default-delivery-mode} 配置；缺省 BOTH。</li>
     * </ul>
     */
    private MqDeliveryMode resolveDeliveryMode(MqDeliveryMode requested) {
        if (requested != null && requested != MqDeliveryMode.DEFAULT) {
            return requested;
        }
        MqDeliveryMode configured = properties.getRedis().getDefaultDeliveryMode();
        return configured != null ? configured : MqDeliveryMode.BOTH;
    }

    private static String resolveMessageId(Map<String, String> headers) {
        String id = headers.get(MqHeaders.MESSAGE_ID);
        return id != null && !id.isBlank() ? id : UNKNOWN_MESSAGE_ID;
    }

    // ============== Pub/Sub ==============

    /**
     * Pub/Sub 订阅。
     * <p>
     * Pub/Sub 受协议限制不携带 framework metadata：
     * </p>
     * <ul>
     *     <li>消息 body 直接作为 raw payload 写入 {@link MqInboundRecord#getBody()}。</li>
     *     <li>headers 为空，nativeMessageId 由 driver 临时生成（UUID）。</li>
     *     <li>消费 runtime 会把 metadata 标记为 synthetic，并就地生成新 traceId。</li>
     *     <li>Pub/Sub 没有 ACK 机制，{@link ConsumeDecision} 仅用于 metrics 与日志。</li>
     * </ul>
     */
    private MqSubscription subscribePubSub(String topic, MqRecordHandler handler) {
        ChannelTopic channelTopic = new ChannelTopic(topic);

        MessageListener listener = (Message message, byte[] pattern) -> {
            long startTime = System.currentTimeMillis();
            String nativeId = UUID.randomUUID().toString();
            try {
                byte[] body = message.getBody();
                MqInboundRecord inbound = MqInboundRecord.builder()
                        .driver(type())
                        .topic(topic)
                        .groupId(null)
                        .key(null)
                        .body(body == null ? new byte[0] : body)
                        .headers(Collections.emptyMap())
                        .nativeMessageId(nativeId)
                        .timestamp(System.currentTimeMillis())
                        .attributes(Map.of(ATTR_MODE, MODE_PUBSUB))
                        .build();

                ConsumeDecision decision = handler.handle(inbound);
                if (decision == null || decision.status() == ConsumeStatus.SUCCESS) {
                    MqMetrics.recordConsumeSuccess(topic, type().name(), PUBSUB_GROUP_PLACEHOLDER, nativeId);
                } else {
                    MqMetrics.recordConsumeFailure(topic, type().name(), PUBSUB_GROUP_PLACEHOLDER, nativeId);
                }
            } catch (Exception e) {
                MqMetrics.recordConsumeFailure(topic, type().name(), PUBSUB_GROUP_PLACEHOLDER, nativeId);
                log.error("Redis Pub/Sub 消费抛出未受控异常: topic={}, nativeId={}", topic, nativeId, e);
            } finally {
                MqMetrics.recordConsumeDuration(topic, type().name(), PUBSUB_GROUP_PLACEHOLDER, nativeId,
                        System.currentTimeMillis() - startTime);
            }
        };

        listenerContainer.addMessageListener(listener, channelTopic);
        log.info("Redis Pub/Sub 开始订阅: topic={}", topic);

        return () -> {
            try {
                listenerContainer.removeMessageListener(listener, channelTopic);
                log.info("Redis Pub/Sub 结束订阅: topic={}", topic);
            } catch (Exception e) {
                log.warn("Redis Pub/Sub 取消订阅失败: topic={}", topic, e);
            }
        };
    }

    // ============== Stream ==============

    /**
     * Stream 订阅。
     */
    private MqSubscription subscribeStream(MqSubscribeSpec spec, MqRecordHandler handler) {
        String topic = spec.getTopic();
        String groupId = spec.getGroupId();
        String consumerName = properties.getRedis().getConsumerName();
        ensureConsumerGroup(topic, groupId);

        StreamSubscription subscription = new StreamSubscription(spec, consumerName, handler);
        executor.execute(subscription::start);
        return subscription;
    }

    /**
     * 确保消费组存在。
     * <p>
     * 使用 {@code XGROUP CREATE ... MKSTREAM} 同时创建 stream 与 group，
     * 不再写占位 entry，避免业务消费看到无意义的初始记录。
     * </p>
     * <p>
     * 仅 BUSYGROUP（消费组已存在）被静默吞掉；其他基础设施异常会上抛，
     * 让订阅启动 fail-fast，避免「订阅已注册但消费循环始终失败」的隐性错误。
     * </p>
     */
    private void ensureConsumerGroup(String topic, String groupId) {
        byte[] streamKey = topic.getBytes(StandardCharsets.UTF_8);
        try {
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(streamKey, groupId,
                            ReadOffset.from(STREAM_OFFSET_BEGIN), true));
            log.info("Redis Stream 创建消费组: topic={}, groupId={}", topic, groupId);
        } catch (RuntimeException e) {
            if (isBusyGroupError(e)) {
                log.debug("Redis Stream 消费组已存在: topic={}, groupId={}", topic, groupId);
                return;
            }
            throw new IllegalStateException(
                    "Redis Stream 创建消费组失败: topic=" + topic + ", groupId=" + groupId, e);
        }
    }

    /**
     * 沿异常链匹配 Redis 返回的 BUSYGROUP 错误标记。
     */
    private static boolean isBusyGroupError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains(REDIS_BUSYGROUP_ERROR)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 把 Redis Stream 的 byte[] 级 entry 转换为 {@link MqInboundRecord}。
     * <ul>
     *     <li>{@link MqHeaders#PAYLOAD_FIELD} 字段 → body（原始 byte[]，不做编码）。</li>
     *     <li>其他字段 → headers（按 UTF-8 解码 name 与 value，与 {@code Map<String,String>} 契约一致）。</li>
     * </ul>
     */
    private MqInboundRecord fromByteRecord(ByteRecord record, String topic, String groupId) {
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];
        Map<byte[], byte[]> entries = record.getValue();
        if (entries != null) {
            for (Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String fieldName = new String(entry.getKey(), StandardCharsets.UTF_8);
                byte[] value = entry.getValue() == null ? new byte[0] : entry.getValue();
                if (MqHeaders.PAYLOAD_FIELD.equals(fieldName)) {
                    body = value;
                } else {
                    headers.put(fieldName, new String(value, StandardCharsets.UTF_8));
                }
            }
        }
        String key = headers.get(MqHeaders.KEY);
        long timestamp;
        try {
            String tsHeader = headers.get(MqHeaders.TIMESTAMP);
            timestamp = tsHeader != null ? Long.parseLong(tsHeader.trim()) : System.currentTimeMillis();
        } catch (NumberFormatException e) {
            timestamp = System.currentTimeMillis();
        }
        return MqInboundRecord.builder()
                .driver(type())
                .topic(topic)
                .groupId(groupId)
                .key(key)
                .body(body)
                .headers(headers)
                .nativeMessageId(record.getId().getValue())
                .timestamp(timestamp)
                .attributes(Map.of(ATTR_MODE, MODE_STREAM))
                .build();
    }

    /**
     * ACK 单条 Stream 记录。
     */
    private void ack(String topic, String groupId, RecordId recordId) {
        byte[] streamKey = topic.getBytes(StandardCharsets.UTF_8);
        try {
            redisTemplate.execute((RedisCallback<Long>) connection ->
                    connection.streamCommands().xAck(streamKey, groupId, recordId));
        } catch (Exception e) {
            log.warn("Redis Stream ACK 失败: topic={}, groupId={}, recordId={}", topic, groupId, recordId, e);
        }
    }

    /**
     * Redis Stream 订阅句柄与消费循环。
     */
    private class StreamSubscription implements MqSubscription {

        private final MqSubscribeSpec spec;
        private final String topic;
        private final byte[] streamKey;
        private final String groupId;
        private final String consumerName;
        private final MqRecordHandler handler;
        private final long maxBackoffMs;

        private volatile boolean running = true;
        private volatile Thread consumerThread;
        private int consecutiveFailures;

        private StreamSubscription(MqSubscribeSpec spec, String consumerName, MqRecordHandler handler) {
            this.spec = spec;
            this.topic = spec.getTopic();
            this.streamKey = topic.getBytes(StandardCharsets.UTF_8);
            this.groupId = spec.getGroupId();
            this.consumerName = consumerName;
            this.handler = handler;
            this.maxBackoffMs = properties.getRetry().getStreamMaxBackoff().toMillis();
        }

        /**
         * 消费循环。
         */
        private void start() {
            this.consumerThread = Thread.currentThread();
            Duration pollTimeout = spec.getPollTimeout() != null ? spec.getPollTimeout() : properties.getRedis().getPollTimeout();
            int batchSize = spec.getBatchSize() != null ? spec.getBatchSize() : properties.getRedis().getBatchSize();
            Duration pendingScanInterval = properties.getRetry().getPendingScanInterval();
            long lastPendingScanTime = System.currentTimeMillis();

            log.info("Redis Stream 开始订阅: topic={}, groupId={}, consumer={}, batch={}",
                    topic, groupId, consumerName, spec.isBatch());

            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastPendingScanTime >= pendingScanInterval.toMillis()) {
                        claimPendingMessages();
                        lastPendingScanTime = now;
                    }

                    List<ByteRecord> records = readBatch(pollTimeout, batchSize);
                    if (records == null || records.isEmpty()) {
                        continue;
                    }

                    if (spec.isBatch()) {
                        handleBatch(records);
                    } else {
                        for (ByteRecord record : records) {
                            handleSingle(record);
                        }
                    }
                    consecutiveFailures = 0;
                } catch (Exception e) {
                    if (!running || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    consecutiveFailures++;
                    long backoffMs = calculateBackoff(consecutiveFailures);
                    log.error("Redis Stream 消费循环异常: topic={}, groupId={}, consecutiveFailures={}, backoffMs={}",
                            topic, groupId, consecutiveFailures, backoffMs, e);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("Redis Stream 结束订阅: topic={}, groupId={}, consumer={}", topic, groupId, consumerName);
        }

        /**
         * 拉取一批消息（byte[] 级）。
         */
        private List<ByteRecord> readBatch(Duration pollTimeout, int batchSize) {
            return redisTemplate.execute((RedisCallback<List<ByteRecord>>) connection ->
                    connection.streamCommands().xReadGroup(
                            Consumer.from(groupId, consumerName),
                            StreamReadOptions.empty().count(batchSize).block(pollTimeout),
                            StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                    ));
        }

        /**
         * 单条消费。
         */
        private void handleSingle(ByteRecord record) {
            long startTime = System.currentTimeMillis();
            String nativeId = record.getId().getValue();
            MqInboundRecord inbound = fromByteRecord(record, topic, groupId);

            ConsumeDecision decision;
            try {
                decision = handler.handle(inbound);
            } catch (Exception e) {
                // handler 已经将异常封装为 ConsumeDecision；走到这里说明实现违反契约。
                log.error("Redis Stream handler 抛出未受控异常: topic={}, groupId={}, recordId={}",
                        topic, groupId, record.getId(), e);
                decision = ConsumeDecision.retry(e);
            }

            applyDecision(record, decision, startTime, nativeId, inbound);
        }

        /**
         * 批量消费。
         */
        private void handleBatch(List<ByteRecord> records) {
            long startTime = System.currentTimeMillis();
            List<MqInboundRecord> inbounds = new ArrayList<>(records.size());
            for (ByteRecord record : records) {
                inbounds.add(fromByteRecord(record, topic, groupId));
            }

            List<ConsumeDecision> decisions;
            try {
                decisions = handler.handleBatch(inbounds);
            } catch (Exception e) {
                log.error("Redis Stream handler.handleBatch 抛出未受控异常: topic={}, groupId={}, size={}",
                        topic, groupId, records.size(), e);
                decisions = Collections.nCopies(records.size(), ConsumeDecision.retry(e));
            }

            // 与 records 长度对齐。
            if (decisions == null || decisions.size() != records.size()) {
                log.error("Redis Stream handler 返回的 decisions 数量与 records 不一致: expected={}, actual={}",
                        records.size(), decisions == null ? "null" : decisions.size());
                ConsumeDecision fallback = ConsumeDecision.retry(new IllegalStateException("decisions size mismatch"));
                decisions = Collections.nCopies(records.size(), fallback);
            }

            for (int i = 0; i < records.size(); i++) {
                ByteRecord record = records.get(i);
                ConsumeDecision decision = decisions.get(i);
                applyDecision(record, decision, startTime, record.getId().getValue(), inbounds.get(i));
            }
        }

        /**
         * 据 {@link ConsumeDecision} 处理 ACK / 重投 / DLQ，并记录 metrics。
         * <p>
         * 跨 driver 一致性约定：
         * </p>
         * <ul>
         *     <li>{@code SUCCESS}：ACK。</li>
         *     <li>{@code BAD_MESSAGE}：ACK，不投 DLQ（payload 已损坏，DLQ 也无法处理）。</li>
         *     <li>{@code DROP}：DLQ + ACK；DLQ 失败时按 {@code commitOnDlqFailure} 决定是否 ACK。</li>
         *     <li>{@code RETRY}：不 ACK，依赖 PEL + reclaim 持久重试（Redis Stream 的原生重试机制）。</li>
         * </ul>
         */
        private void applyDecision(ByteRecord record, ConsumeDecision decision,
                                   long startTime, String nativeId, MqInboundRecord inbound) {
            String driverName = type().name();
            if (decision == null) {
                decision = ConsumeDecision.success();
            }
            switch (decision.status()) {
                case SUCCESS -> {
                    ack(topic, groupId, record.getId());
                    MqMetrics.recordConsumeSuccess(topic, driverName, groupId, nativeId);
                }
                case BAD_MESSAGE -> {
                    // 坏消息：ACK 释放位点，避免阻塞。
                    ack(topic, groupId, record.getId());
                    MqMetrics.recordConsumeFailure(topic, driverName, groupId, nativeId);
                    log.warn("Redis Stream 坏消息（已 ACK 跳过）: topic={}, groupId={}, recordId={}",
                            topic, groupId, record.getId(), decision.error());
                }
                case DROP -> {
                    // 不可重试业务失败：先入 DLQ，DLQ 可靠后才 ACK。
                    handleTerminalFailure(record, inbound, decision.error(), nativeId, driverName);
                }
                case RETRY -> {
                    // 可重试：不 ACK，消息保留在 PEL，等待 pending reclaim。
                    MqMetrics.recordConsumeFailure(topic, driverName, groupId, nativeId);
                    log.warn("Redis Stream 可重试消费失败（保留在 PEL）: topic={}, groupId={}, recordId={}",
                            topic, groupId, record.getId(), decision.error());
                }
                default -> {
                    // 防御：未识别状态视为成功。
                    ack(topic, groupId, record.getId());
                }
            }
            MqMetrics.recordConsumeDuration(topic, driverName, groupId, nativeId,
                    System.currentTimeMillis() - startTime);
        }

        /**
         * 终态失败处理：仅当 DLQ 安全落库（或被显式禁用 / 显式授权丢弃）时才 ACK。
         * <p>
         * 默认 {@code commitOnDlqFailure=false}：DLQ 发送失败时不 ACK，
         * 消息留在 PEL 等待下一轮 reclaim 重新投递，避免静默丢消息。
         * </p>
         */
        private void handleTerminalFailure(ByteRecord record, MqInboundRecord inbound,
                                           Throwable error, String nativeId, String driverName) {
            DlqOutcome outcome = sendToDlq(record, inbound, error);
            MqMetrics.recordConsumeFailure(topic, driverName, groupId, nativeId);
            switch (outcome) {
                case DLQ_DISABLED, DLQ_SUCCESS -> ack(topic, groupId, record.getId());
                case DLQ_FAILED -> {
                    if (properties.getRetry().isCommitOnDlqFailure()) {
                        log.error("Redis Stream DLQ 发送失败但配置要求强制 ACK，消息将丢失: topic={}, groupId={}, recordId={}",
                                topic, groupId, record.getId());
                        ack(topic, groupId, record.getId());
                    } else {
                        log.warn("Redis Stream DLQ 发送失败，保留 PEL 等待 reclaim (commitOnDlqFailure=false): topic={}, groupId={}, recordId={}",
                                topic, groupId, record.getId());
                    }
                }
            }
        }

        /**
         * 将不可重试消息写入 {@code <topic><dlqTopicSuffix>} Stream。
         * <ul>
         *     <li>原始 entry 字段全部复制；payload 字节级保留。</li>
         *     <li>追加诊断 header：原 topic / record id / 入队时间戳 / 错误类名。</li>
         * </ul>
         */
        private DlqOutcome sendToDlq(ByteRecord record, MqInboundRecord inbound, Throwable error) {
            MqProperties.Retry retryConfig = properties.getRetry();
            if (!retryConfig.isDlqEnabled()) {
                log.warn("Redis Stream DLQ 未启用，消息将被丢弃: topic={}, groupId={}, recordId={}",
                        topic, groupId, record.getId());
                return DlqOutcome.DLQ_DISABLED;
            }
            String dlqTopic = topic + retryConfig.getDlqTopicSuffix();
            byte[] dlqStreamKey = dlqTopic.getBytes(StandardCharsets.UTF_8);
            try {
                Map<byte[], byte[]> fields = new LinkedHashMap<>();
                Map<byte[], byte[]> originalFields = record.getValue();
                if (originalFields != null) {
                    for (Map.Entry<byte[], byte[]> entry : originalFields.entrySet()) {
                        if (entry.getKey() != null) {
                            fields.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                fields.put(DLQ_HEADER_ORIGINAL_TOPIC.getBytes(StandardCharsets.UTF_8),
                        topic.getBytes(StandardCharsets.UTF_8));
                fields.put(DLQ_HEADER_ORIGINAL_RECORD_ID.getBytes(StandardCharsets.UTF_8),
                        record.getId().getValue().getBytes(StandardCharsets.UTF_8));
                fields.put(DLQ_HEADER_TIMESTAMP.getBytes(StandardCharsets.UTF_8),
                        Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
                if (error != null) {
                    fields.put(DLQ_HEADER_ERROR.getBytes(StandardCharsets.UTF_8),
                            error.getClass().getName().getBytes(StandardCharsets.UTF_8));
                }
                redisTemplate.execute((RedisCallback<RecordId>) connection ->
                        connection.streamCommands().xAdd(dlqStreamKey, fields));
                log.info("Redis Stream 消息已发送到 DLQ: topic={}, dlqTopic={}, recordId={}",
                        topic, dlqTopic, record.getId());
                return DlqOutcome.DLQ_SUCCESS;
            } catch (Exception e) {
                log.error("Redis Stream 发送到 DLQ 失败: topic={}, dlqTopic={}, recordId={}",
                        topic, dlqTopic, record.getId(), e);
                return DlqOutcome.DLQ_FAILED;
            }
        }

        /**
         * 扫描并认领 pending 消息。
         */
        private void claimPendingMessages() {
            try {
                Duration pendingTimeout = properties.getRetry().getPendingTimeout();
                long minIdleTimeMs = pendingTimeout.toMillis();
                long pendingBatchSize = (long) properties.getRedis().getPendingClaimBatchSize();

                PendingMessages pendingMessages = redisTemplate.execute((RedisCallback<PendingMessages>) connection ->
                        connection.streamCommands().xPending(streamKey, groupId,
                                XPendingOptions.range(Range.unbounded(), pendingBatchSize)));
                if (pendingMessages == null || pendingMessages.isEmpty()) {
                    return;
                }
                for (PendingMessage pendingMessage : pendingMessages) {
                    long idleTime = pendingMessage.getElapsedTimeSinceLastDelivery().toMillis();
                    if (idleTime < minIdleTimeMs) {
                        continue;
                    }
                    claimAndConsume(pendingMessage, minIdleTimeMs);
                }
            } catch (Exception e) {
                log.debug("Redis Stream pending 扫描失败: topic={}, groupId={}, error={}",
                        topic, groupId, e.getMessage());
            }
        }

        /**
         * 认领单条 pending 消息并重新消费。
         */
        private void claimAndConsume(PendingMessage pendingMessage, long minIdleTimeMs) {
            try {
                List<ByteRecord> claimedRecords = redisTemplate.execute((RedisCallback<List<ByteRecord>>) connection ->
                        connection.streamCommands().xClaim(streamKey, groupId, consumerName,
                                XClaimOptions.minIdle(Duration.ofMillis(minIdleTimeMs))
                                        .ids(RecordId.of(pendingMessage.getIdAsString()))));
                if (claimedRecords == null || claimedRecords.isEmpty()) {
                    return;
                }
                log.info("Redis Stream 认领 pending 消息: topic={}, groupId={}, count={}",
                        topic, groupId, claimedRecords.size());
                if (spec.isBatch()) {
                    handleBatch(claimedRecords);
                } else {
                    for (ByteRecord record : claimedRecords) {
                        handleSingle(record);
                    }
                }
            } catch (Exception e) {
                log.warn("Redis Stream 认领消息失败: topic={}, groupId={}, recordId={}",
                        topic, groupId, pendingMessage.getIdAsString(), e);
            }
        }

        /**
         * 计算指数退避：1s, 2s, 4s, 8s ... 上限 {@link #maxBackoffMs}。
         * <p>
         * 位移量限制在 {@link #STREAM_BACKOFF_MAX_SHIFT} 以内，避免 {@code 1L << n} 溢出。
         * </p>
         */
        private long calculateBackoff(int failures) {
            if (failures <= 0) {
                return STREAM_BACKOFF_BASE_MS;
            }
            long backoff = STREAM_BACKOFF_BASE_MS * (1L << Math.min(failures - 1, STREAM_BACKOFF_MAX_SHIFT));
            return Math.min(backoff, maxBackoffMs);
        }

        @Override
        public void close() {
            running = false;
            Thread thread = this.consumerThread;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }
}
