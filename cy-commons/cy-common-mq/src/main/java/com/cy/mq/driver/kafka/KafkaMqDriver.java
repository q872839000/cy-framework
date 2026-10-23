package com.cy.mq.driver.kafka;

import com.cy.mq.config.MqProperties;
import com.cy.mq.core.ConsumeDecision;
import com.cy.mq.core.ConsumeStatus;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqHeaders;
import com.cy.mq.core.MqInboundRecord;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqSubscription;
import com.cy.mq.core.MqTopicSpec;
import com.cy.mq.exception.MqPublishException;
import com.cy.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka MQ 驱动。
 * <p>
 * 使用 Kafka 原生 headers 通道传输 framework metadata 与业务自定义 headers，
 * record value 仅承载业务 payload。这样：
 * </p>
 * <ul>
 *     <li>外部生产者发送原生 Kafka 消息时（无 framework headers）也能被消费 runtime
 *     正确处理：metadata 由 broker 元信息合成，payload 直接作为 raw body。</li>
 *     <li>跨语言消费方仅关心 record value 即可，不必理解 framework metadata。</li>
 *     <li>framework metadata 与业务 payload 在网络字节层完全解耦。</li>
 * </ul>
 *
 * @author pcy
 */
@Slf4j
public class KafkaMqDriver implements MqDriver {

    /**
     * messageId 不存在时用于 metrics / 日志诊断的占位值。
     */
    private static final String UNKNOWN_MESSAGE_ID = "unknown";

    /**
     * Kafka topic 名最大长度（{@code org.apache.kafka.common.internals.Topic.MAX_NAME_LENGTH}）。
     */
    private static final int KAFKA_TOPIC_NAME_MAX_LENGTH = 249;

    /**
     * 同步发送 DLQ 记录的超时时间，避免异常场景下消费循环被无限期阻塞。
     */
    private static final Duration DLQ_SEND_TIMEOUT = Duration.ofSeconds(30);

    /**
     * DLQ 附加诊断 header：原始 topic。
     */
    private static final String DLQ_HEADER_ORIGINAL_TOPIC = "x-dlq-original-topic";

    /**
     * DLQ 附加诊断 header：原始 partition。
     */
    private static final String DLQ_HEADER_ORIGINAL_PARTITION = "x-dlq-original-partition";

    /**
     * DLQ 附加诊断 header：原始 offset。
     */
    private static final String DLQ_HEADER_ORIGINAL_OFFSET = "x-dlq-original-offset";

    /**
     * DLQ 附加诊断 header：DLQ 入队时间戳（毫秒）。
     */
    private static final String DLQ_HEADER_TIMESTAMP = "x-dlq-timestamp";

    /**
     * DLQ 附加诊断 header：原始消费异常类名。
     */
    private static final String DLQ_HEADER_ERROR = "x-dlq-error";

    /**
     * MQ 配置。
     */
    private final MqProperties properties;

    /**
     * Kafka 发送模板。
     */
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    /**
     * Kafka 消费者工厂。
     */
    private final ConsumerFactory<String, byte[]> consumerFactory;

    /**
     * 消费执行器。
     */
    private final Executor executor;

    /**
     * 构造函数。
     *
     * @param kafkaTemplate    Kafka 发送模板
     * @param consumerFactory  Kafka 消费工厂
     * @param properties       MQ 配置
     * @param executor         消费执行器
     */
    public KafkaMqDriver(KafkaTemplate<String, byte[]> kafkaTemplate,
                         ConsumerFactory<String, byte[]> consumerFactory,
                         MqProperties properties,
                         Executor executor) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public MqDriverType type() {
        return MqDriverType.KAFKA;
    }

    /**
     * DLQ 发送结果。driver 据此决定是否 commit 原消息。
     */
    private enum DlqOutcome {
        /** DLQ 未启用，调用方明确选择丢弃 → commit。 */
        DLQ_DISABLED,
        /** DLQ 发送成功 → commit。 */
        DLQ_SUCCESS,
        /** DLQ 发送失败 → 由 commitOnDlqFailure 决定是否 commit。 */
        DLQ_FAILED
    }

    @Override
    public void publish(MqOutboundRecord record) {
        if (record == null || record.getTopic() == null) {
            return;
        }

        String messageId = resolveMessageId(record.safeHeaders());
        long startTime = System.currentTimeMillis();

        byte[] value = record.getBody() == null ? new byte[0] : record.getBody();
        ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(record.getTopic(), record.getKey(), value);
        // headers 写入 Kafka 原生 headers 通道。
        if (record.getHeaders() != null) {
            for (Map.Entry<String, String> entry : record.getHeaders().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                producerRecord.headers().add(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }

        try {
            // 同步发布以让上层感知失败。
            var sendResult = kafkaTemplate.send(producerRecord)
                    .get(properties.getKafka().getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);

            MqMetrics.recordPublishSuccess(record.getTopic(), type().name(), messageId);
            MqMetrics.recordPublishDuration(record.getTopic(), type().name(), messageId,
                    System.currentTimeMillis() - startTime);

            if (log.isDebugEnabled()) {
                log.debug("Kafka MQ 发布成功: topic={}, msgId={}, partition={}, offset={}",
                        record.getTopic(), messageId,
                        sendResult.getRecordMetadata().partition(),
                        sendResult.getRecordMetadata().offset());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(record.getTopic(), messageId, startTime);
            throw new MqPublishException(record.getTopic(), messageId,
                    "Kafka MQ 发布被中断: topic=" + record.getTopic(), e);
        } catch (TimeoutException e) {
            recordFailure(record.getTopic(), messageId, startTime);
            throw new MqPublishException(record.getTopic(), messageId,
                    "Kafka MQ 发布超时: topic=" + record.getTopic(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            recordFailure(record.getTopic(), messageId, startTime);
            throw new MqPublishException(record.getTopic(), messageId,
                    "Kafka MQ 发布失败: topic=" + record.getTopic(), cause);
        }
    }

    private void recordFailure(String topic, String messageId, long startTime) {
        MqMetrics.recordPublishFailure(topic, type().name(), messageId);
        MqMetrics.recordPublishDuration(topic, type().name(), messageId, System.currentTimeMillis() - startTime);
    }

    @Override
    public void createTopics(Iterable<MqTopicSpec> topics) {
        if (topics == null) {
            return;
        }

        Map<String, NewTopic> topicsByName = new LinkedHashMap<>();
        for (MqTopicSpec topic : topics) {
            if (topic == null || topic.name() == null || topic.name().isBlank()) {
                continue;
            }
            topicsByName.putIfAbsent(topic.name(), toNewTopic(topic));
        }
        List<NewTopic> newTopics = new ArrayList<>(topicsByName.values());
        if (newTopics.isEmpty()) {
            return;
        }

        try (AdminClient adminClient = createAdminClient()) {
            Map<String, Throwable> failures = new LinkedHashMap<>();
            List<String> created = new ArrayList<>();
            var createResults = adminClient.createTopics(newTopics).values();
            for (Map.Entry<String, KafkaFuture<Void>> entry : createResults.entrySet()) {
                try {
                    entry.getValue().get();
                    created.add(entry.getKey());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (!(cause instanceof TopicExistsException)) {
                        failures.put(entry.getKey(), cause != null ? cause : e);
                    }
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("Kafka MQ 创建 Topic 失败: " + failures);
            }
            if (!created.isEmpty()) {
                log.info("Kafka MQ 创建 Topic: {}", created);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka MQ 创建 Topic 被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka MQ 创建 Topic 失败", e);
        }
    }

    @Override
    public MqSubscription subscribe(MqSubscribeSpec spec, MqRecordHandler handler) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(spec.getTopic(), "spec.topic must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        String groupId = resolveGroupId(spec.getGroupId());
        KafkaSubscription subscription = new KafkaSubscription(spec, groupId, handler);
        executor.execute(subscription::start);
        return subscription;
    }

    /**
     * 解析消费组：未指定时使用全局默认。
     */
    private String resolveGroupId(String groupId) {
        return (groupId == null || groupId.isBlank())
                ? properties.getKafka().getDefaultGroupId()
                : groupId;
    }

    /**
     * 创建消费者。
     */
    private Consumer<String, byte[]> createConsumer(String groupId) {
        return consumerFactory.createConsumer(groupId, null);
    }

    /**
     * 创建 Kafka 管理客户端。
     */
    protected AdminClient createAdminClient() {
        return AdminClient.create(consumerFactory.getConfigurationProperties());
    }

    /**
     * 转换主题定义为 Kafka NewTopic。
     */
    private NewTopic toNewTopic(MqTopicSpec topic) {
        validateTopicName(topic.name());
        NewTopic newTopic = new NewTopic(topic.name(), Optional.ofNullable(topic.partitions()), Optional.ofNullable(topic.replicas()));
        if (!topic.configs().isEmpty()) {
            newTopic.configs(topic.configs());
        }
        return newTopic;
    }

    /**
     * 校验 Kafka 主题名是否合法。
     */
    private void validateTopicName(String topicName) {
        if (topicName == null || topicName.isBlank()) {
            throw new IllegalArgumentException("topic name must not be blank");
        }
        if (topicName.length() > KAFKA_TOPIC_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Kafka topic 名长度不能超过 "
                    + KAFKA_TOPIC_NAME_MAX_LENGTH + ": " + topicName);
        }
        for (int i = 0; i < topicName.length(); i++) {
            char c = topicName.charAt(i);
            boolean asciiAlphaNumeric = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (!asciiAlphaNumeric && c != '.' && c != '_' && c != '-') {
                throw new IllegalArgumentException("Kafka topic 名包含非法字符，仅支持字母数字、'.'、'_'、'-': " + topicName);
            }
        }
    }

    private static String resolveMessageId(Map<String, String> headers) {
        String id = headers.get(MqHeaders.MESSAGE_ID);
        return id != null && !id.isBlank() ? id : UNKNOWN_MESSAGE_ID;
    }

    /**
     * 把 Kafka {@link ConsumerRecord} 转换为 {@link MqInboundRecord}。
     */
    private MqInboundRecord toInboundRecord(ConsumerRecord<String, byte[]> record, String groupId) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            if (header.key() == null) {
                continue;
            }
            byte[] value = header.value();
            headers.put(header.key(), value == null ? "" : new String(value, StandardCharsets.UTF_8));
        }
        byte[] body = record.value() == null ? new byte[0] : record.value();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("partition", record.partition());
        attributes.put("offset", record.offset());
        return MqInboundRecord.builder()
                .driver(type())
                .topic(record.topic())
                .groupId(groupId)
                .key(record.key())
                .body(body)
                .headers(headers)
                .nativeMessageId(record.partition() + "-" + record.offset())
                .timestamp(record.timestamp())
                .attributes(Collections.unmodifiableMap(attributes))
                .build();
    }

    /**
     * Kafka 订阅句柄与消费循环。
     */
    private class KafkaSubscription implements MqSubscription {

        private final MqSubscribeSpec spec;
        private final String topic;
        private final String groupId;
        private final MqRecordHandler handler;
        private final AtomicBoolean running = new AtomicBoolean(true);

        private Consumer<String, byte[]> kafkaConsumer;
        private volatile Thread consumerThread;

        private KafkaSubscription(MqSubscribeSpec spec, String groupId, MqRecordHandler handler) {
            this.spec = spec;
            this.topic = spec.getTopic();
            this.groupId = groupId;
            this.handler = handler;
        }

        /**
         * 消费循环。
         */
        private void start() {
            this.consumerThread = Thread.currentThread();
            Duration pollTimeout = spec.getPollTimeout() != null
                    ? spec.getPollTimeout()
                    : (properties.getKafka().getPollTimeout() != null
                            ? properties.getKafka().getPollTimeout()
                            : Duration.ofSeconds(1));

            try {
                this.kafkaConsumer = createConsumer(groupId);
                kafkaConsumer.subscribe(Collections.singletonList(topic));
                log.info("Kafka MQ 开始订阅: topic={}, groupId={}, batch={}",
                        topic, groupId, spec.isBatch());

                while (running.get()) {
                    ConsumerRecords<String, byte[]> records = kafkaConsumer.poll(pollTimeout);
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    if (spec.isBatch()) {
                        handleBatch(records);
                    } else {
                        for (ConsumerRecord<String, byte[]> record : records) {
                            if (!handleSingle(record)) {
                                break;
                            }
                        }
                    }
                }
            } catch (WakeupException e) {
                if (running.get()) {
                    log.error("Kafka MQ 消费循环被意外唤醒: topic={}, groupId={}", topic, groupId, e);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Kafka MQ 消费循环异常: topic={}, groupId={}", topic, groupId, e);
                }
            } finally {
                closeConsumer();
                log.info("Kafka MQ 结束订阅: topic={}, groupId={}", topic, groupId);
            }
        }

        /**
         * 单条消费。
         *
         * @return {@code true} 表示循环可继续；{@code false} 表示因关闭等原因应中止 poll。
         */
        private boolean handleSingle(ConsumerRecord<String, byte[]> record) {
            long startTime = System.currentTimeMillis();
            MqInboundRecord inbound = toInboundRecord(record, groupId);
            String messageId = resolveMessageId(inbound.safeHeaders());

            ConsumeDecision decision = invokeWithRetry(inbound, true);
            // running 已停止时，invokeWithRetry 可能返回 RETRY；不 commit，等待下次启动重新投递。
            if (!running.get() && decision != null && decision.status() == ConsumeStatus.RETRY) {
                return false;
            }
            return applyDecision(record, inbound, decision, startTime, messageId);
        }

        /**
         * 批量消费。
         */
        private void handleBatch(ConsumerRecords<String, byte[]> records) {
            long startTime = System.currentTimeMillis();
            List<ConsumerRecord<String, byte[]>> kafkaRecords = new ArrayList<>(records.count());
            List<MqInboundRecord> inbounds = new ArrayList<>(records.count());
            for (ConsumerRecord<String, byte[]> record : records) {
                kafkaRecords.add(record);
                inbounds.add(toInboundRecord(record, groupId));
            }

            List<ConsumeDecision> decisions = invokeBatchWithRetry(inbounds);
            if (decisions == null || decisions.size() != kafkaRecords.size()) {
                log.error("Kafka MQ handler 返回的 decisions 数量与 records 不一致: expected={}, actual={}",
                        kafkaRecords.size(), decisions == null ? "null" : decisions.size());
                ConsumeDecision fallback = ConsumeDecision.retry(new IllegalStateException("decisions size mismatch"));
                decisions = Collections.nCopies(kafkaRecords.size(), fallback);
            }

            List<TopicPartition> blockedPartitions = new ArrayList<>();
            // 逐条 apply 决策（共享 startTime 用于批量耗时统计）。
            for (int i = 0; i < kafkaRecords.size(); i++) {
                ConsumerRecord<String, byte[]> record = kafkaRecords.get(i);
                TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
                if (blockedPartitions.contains(topicPartition)) {
                    continue;
                }
                MqInboundRecord inbound = inbounds.get(i);
                ConsumeDecision decision = decisions.get(i);
                if (!running.get() && decision != null && decision.status() == ConsumeStatus.RETRY) {
                    return;
                }
                String messageId = resolveMessageId(inbound.safeHeaders());
                if (!applyDecision(record, inbound, decision, startTime, messageId)) {
                    blockedPartitions.add(topicPartition);
                }
            }
        }

        /**
         * 调用 handler，对 RETRY 决策按配置做指数退避重试。
         *
         * @param inbound      入站记录
         * @param invokeIfStop 是否在 running=false 时仍调用一次（用于尽量消费完当前批次）
         * @return 最终决策
         */
        private ConsumeDecision invokeWithRetry(MqInboundRecord inbound, boolean invokeIfStop) {
            MqProperties.Retry retryConfig = properties.getRetry();
            int maxRetries = retryConfig.getMaxRetries();
            long backoff = retryConfig.getInitialBackoff().toMillis();
            double multiplier = retryConfig.getBackoffMultiplier();
            long maxBackoff = retryConfig.getMaxBackoff().toMillis();

            ConsumeDecision decision = null;
            int attempt = 0;
            while (maxRetries < 0 || attempt <= maxRetries) {
                if (!invokeIfStop && !running.get()) {
                    return decision;
                }
                try {
                    decision = handler.handle(inbound);
                } catch (Exception e) {
                    // handler 应当封装异常为 ConsumeDecision；走到这里是契约违反。
                    log.error("Kafka MQ handler 抛出未受控异常: topic={}, groupId={}, nativeId={}",
                            topic, groupId, inbound.getNativeMessageId(), e);
                    decision = ConsumeDecision.retry(e);
                }
                if (decision == null) {
                    decision = ConsumeDecision.success();
                }
                // 非 RETRY 直接返回。
                if (decision.status() != ConsumeStatus.RETRY) {
                    return decision;
                }
                if (maxRetries >= 0 && attempt >= maxRetries) {
                    break;
                }
                if (!running.get()) {
                    return decision;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return decision;
                }
                backoff = Math.min((long) (backoff * multiplier), maxBackoff);
                attempt++;
            }
            return decision;
        }

        /**
         * 批量调用 handler，仅对 RETRY 位置的记录做指数退避重试。
         * <p>
         * 设计不变量：同一批中已出现的 SUCCESS / BAD_MESSAGE / DROP 决策不被重试覆盖；
         * 仅对返回 RETRY 的记录以子集重新调用 {@code handleBatch}，并把新决策按位回填。
         * 达到 maxRetries 后仍为 RETRY 的位置由 {@code applyDecision} 按重试耗尽语义处理（DLQ + commit）。
         * </p>
         */
        private List<ConsumeDecision> invokeBatchWithRetry(List<MqInboundRecord> inbounds) {
            MqProperties.Retry retryConfig = properties.getRetry();
            int maxRetries = retryConfig.getMaxRetries();
            long backoff = retryConfig.getInitialBackoff().toMillis();
            double multiplier = retryConfig.getBackoffMultiplier();
            long maxBackoff = retryConfig.getMaxBackoff().toMillis();

            // 1. 首次调用拿到初始决策。
            List<ConsumeDecision> decisions = invokeHandleBatchSafely(inbounds);

            int attempt = 0;
            while (maxRetries < 0 || attempt < maxRetries) {
                if (!running.get()) {
                    return decisions;
                }
                // 2. 收集仍为 RETRY 的位置，以该子集进入下一轮重试。
                List<Integer> retryIndices = new ArrayList<>();
                for (int i = 0; i < decisions.size(); i++) {
                    ConsumeDecision d = decisions.get(i);
                    if (d != null && d.status() == ConsumeStatus.RETRY) {
                        retryIndices.add(i);
                    }
                }
                if (retryIndices.isEmpty()) {
                    return decisions;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return decisions;
                }
                backoff = Math.min((long) (backoff * multiplier), maxBackoff);
                attempt++;

                List<MqInboundRecord> retryInbounds = new ArrayList<>(retryIndices.size());
                for (Integer idx : retryIndices) {
                    retryInbounds.add(inbounds.get(idx));
                }
                List<ConsumeDecision> retryDecisions = invokeHandleBatchSafely(retryInbounds);
                if (retryDecisions.size() != retryInbounds.size()) {
                    log.error("Kafka MQ handler.handleBatch 重试返回决策数量不一致: expected={}, actual={}",
                            retryInbounds.size(), retryDecisions.size());
                    ConsumeDecision fallback = ConsumeDecision.retry(new IllegalStateException("retry decisions size mismatch"));
                    retryDecisions = Collections.nCopies(retryInbounds.size(), fallback);
                }
                // 3. 按位回填；其他位置的决策保持不变。
                for (int j = 0; j < retryIndices.size(); j++) {
                    decisions.set(retryIndices.get(j), retryDecisions.get(j));
                }
            }
            return decisions;
        }

        /**
         * 安全调用 {@link MqRecordHandler#handleBatch(List)}，返回列表与入参等长，且不为 {@code null}。
         */
        private List<ConsumeDecision> invokeHandleBatchSafely(List<MqInboundRecord> inbounds) {
            List<ConsumeDecision> decisions;
            try {
                decisions = handler.handleBatch(inbounds);
            } catch (Exception e) {
                log.error("Kafka MQ handler.handleBatch 抛出未受控异常: topic={}, groupId={}, size={}",
                        topic, groupId, inbounds.size(), e);
                return new ArrayList<>(Collections.nCopies(inbounds.size(), ConsumeDecision.retry(e)));
            }
            if (decisions == null || decisions.size() != inbounds.size()) {
                log.error("Kafka MQ handler.handleBatch 返回决策数量与 records 不一致: expected={}, actual={}",
                        inbounds.size(), decisions == null ? "null" : decisions.size());
                ConsumeDecision fallback = ConsumeDecision.retry(new IllegalStateException("decisions size mismatch"));
                return new ArrayList<>(Collections.nCopies(inbounds.size(), fallback));
            }
            // 拷贝为可变列表，便于后续按位回填。
            return new ArrayList<>(decisions);
        }

        /**
         * 据 {@link ConsumeDecision} 处理 commit / DLQ / metrics。
         */
        private boolean applyDecision(ConsumerRecord<String, byte[]> record, MqInboundRecord inbound,
                                      ConsumeDecision decision, long startTime, String messageId) {
            String driverName = type().name();
            boolean continuePartition = true;
            if (decision == null) {
                decision = ConsumeDecision.success();
            }
            switch (decision.status()) {
                case SUCCESS -> {
                    commitRecord(record);
                    MqMetrics.recordConsumeSuccess(record.topic(), driverName, groupId, messageId);
                }
                case BAD_MESSAGE -> {
                    // 解码失败：commit 跳过；不投 DLQ（因为 payload 已损坏）。
                    commitRecord(record);
                    MqMetrics.recordConsumeFailure(record.topic(), driverName, groupId, messageId);
                    log.warn("Kafka MQ 坏消息（已 commit 跳过）: topic={}, groupId={}, partition={}, offset={}",
                            record.topic(), groupId, record.partition(), record.offset(), decision.error());
                }
                case DROP -> {
                    // 不可重试业务失败：先入 DLQ，DLQ 可靠后才 commit。
                    continuePartition = handleTerminalFailure(record, inbound, decision.error(), messageId, driverName, "DROP");
                }
                case RETRY -> {
                    // 重试已经在 invokeWithRetry 中耗尽：同 DROP 路径 — DLQ 可靠后 commit。
                    log.error("Kafka MQ 消费失败已达最大重试次数: topic={}, groupId={}, partition={}, offset={}, messageId={}",
                            record.topic(), groupId, record.partition(), record.offset(), messageId, decision.error());
                    continuePartition = handleTerminalFailure(record, inbound, decision.error(), messageId, driverName, "RETRY-exhausted");
                }
                default -> {
                    // 防御：未知状态视为成功。
                    commitRecord(record);
                }
            }
            MqMetrics.recordConsumeDuration(record.topic(), driverName, groupId, messageId,
                    System.currentTimeMillis() - startTime);
            return continuePartition;
        }

        /**
         * 统一的终态失败处理：DROP 与 RETRY 耗尽均使用。
         * <p>
         * 不变量：消息丢失只在出现以下情形之一时才提交 offset：
         * </p>
         * <ul>
         *     <li>DLQ 未启用：业务明确选择丢弃。</li>
         *     <li>DLQ 发送成功：消息已安全落入 DLQ。</li>
         *     <li>DLQ 失败且 {@code commitOnDlqFailure=true}：调用方显式授权丢弃。</li>
         * </ul>
         * <p>
         * 默认 {@code commitOnDlqFailure=false}：此时不 commit，partition 在下一轮 poll 中
         * 重新拼到该 record，避免静默丟消息。
         * </p>
         */
        private boolean handleTerminalFailure(ConsumerRecord<String, byte[]> record, MqInboundRecord inbound,
                                              Throwable error, String messageId, String driverName, String reason) {
            DlqOutcome outcome = sendToDlq(record, inbound, error);
            MqMetrics.recordConsumeFailure(record.topic(), driverName, groupId, messageId);
            switch (outcome) {
                case DLQ_DISABLED, DLQ_SUCCESS -> {
                    commitRecord(record);
                    return true;
                }
                case DLQ_FAILED -> {
                    if (properties.getRetry().isCommitOnDlqFailure()) {
                        log.error("Kafka MQ DLQ 发送失败但配置要求强制提交 offset，消息将丢失: topic={}, groupId={}, partition={}, offset={}, reason={}",
                                record.topic(), groupId, record.partition(), record.offset(), reason);
                        commitRecord(record);
                        return true;
                    } else {
                        log.warn("Kafka MQ DLQ 发送失败，保留 offset 等待重递 (commitOnDlqFailure=false): topic={}, groupId={}, partition={}, offset={}, reason={}",
                                record.topic(), groupId, record.partition(), record.offset(), reason);
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * 发送消息到死信队列。
         *
         * @return DLQ 发送结果，由调用方决定是否 commit。
         */
        private DlqOutcome sendToDlq(ConsumerRecord<String, byte[]> record, MqInboundRecord inbound, Throwable error) {
            MqProperties.Retry retryConfig = properties.getRetry();
            if (!retryConfig.isDlqEnabled()) {
                log.warn("Kafka MQ DLQ 未启用，消息将被丢弃: topic={}, groupId={}, partition={}, offset={}",
                        topic, groupId, record.partition(), record.offset());
                return DlqOutcome.DLQ_DISABLED;
            }
            String dlqTopic = topic + retryConfig.getDlqTopicSuffix();
            try {
                ProducerRecord<String, byte[]> dlqRecord = new ProducerRecord<>(dlqTopic, record.key(), record.value());
                // 复制原始 headers，并附加 DLQ 元信息。
                if (record.headers() != null) {
                    for (Header header : record.headers()) {
                        if (header.key() != null) {
                            dlqRecord.headers().add(header.key(), header.value());
                        }
                    }
                }
                dlqRecord.headers().add(DLQ_HEADER_ORIGINAL_TOPIC, topic.getBytes(StandardCharsets.UTF_8));
                dlqRecord.headers().add(DLQ_HEADER_ORIGINAL_PARTITION,
                        String.valueOf(record.partition()).getBytes(StandardCharsets.UTF_8));
                dlqRecord.headers().add(DLQ_HEADER_ORIGINAL_OFFSET,
                        String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8));
                dlqRecord.headers().add(DLQ_HEADER_TIMESTAMP,
                        String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
                if (error != null) {
                    dlqRecord.headers().add(DLQ_HEADER_ERROR,
                            error.getClass().getName().getBytes(StandardCharsets.UTF_8));
                }
                kafkaTemplate.send(dlqRecord).get(DLQ_SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                log.info("Kafka MQ 消息已发送到 DLQ: topic={}, dlqTopic={}, partition={}, offset={}",
                        topic, dlqTopic, record.partition(), record.offset());
                return DlqOutcome.DLQ_SUCCESS;
            } catch (Exception e) {
                log.error("Kafka MQ 发送到 DLQ 失败: topic={}, dlqTopic={}, partition={}, offset={}",
                        topic, dlqTopic, record.partition(), record.offset(), e);
                return DlqOutcome.DLQ_FAILED;
            }
        }

        /**
         * 提交单条消息位点。
         */
        private void commitRecord(ConsumerRecord<String, byte[]> record) {
            Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
            offsets.put(new TopicPartition(record.topic(), record.partition()),
                    new OffsetAndMetadata(record.offset() + 1));
            commitOffsets(offsets);
        }

        /**
         * 同步提交位点。
         */
        private void commitOffsets(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (offsets.isEmpty() || kafkaConsumer == null) {
                return;
            }
            try {
                kafkaConsumer.commitSync(offsets);
            } catch (Exception e) {
                log.warn("Kafka MQ commit 失败: groupId={}, offsets={}", groupId, offsets, e);
            }
        }

        private void closeConsumer() {
            try {
                if (kafkaConsumer != null) {
                    kafkaConsumer.close();
                }
            } catch (Exception e) {
                log.debug("Kafka MQ 关闭消费者异常: topic={}, groupId={}", topic, groupId, e);
            }
        }

        @Override
        public void close() {
            running.set(false);
            try {
                if (kafkaConsumer != null) {
                    kafkaConsumer.wakeup();
                }
                Thread thread = this.consumerThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt();
                    thread.join(5000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("等待 Kafka 消费线程退出被中断: topic={}, groupId={}", topic, groupId);
            } catch (Exception e) {
                log.debug("Kafka MQ 关闭异常: topic={}, groupId={}", topic, groupId, e);
            }
        }
    }
}
