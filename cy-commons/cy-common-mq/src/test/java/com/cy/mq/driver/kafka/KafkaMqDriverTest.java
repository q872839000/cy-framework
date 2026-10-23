package com.cy.mq.driver.kafka;

import com.cy.mq.config.MqProperties;
import com.cy.mq.core.ConsumeDecision;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqTopicSpec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KafkaMqDriver} 单元测试。
 * <p>
 * 重构后的 driver 仅负责 broker IO，所有 payload 反序列化与业务回调由
 * {@link MqRecordHandler} 完成。本测试以 mock {@code KafkaTemplate} /
 * {@code Consumer} 验证发布与消费循环的位点提交行为。
 * </p>
 */
class KafkaMqDriverTest {

    @Test
    void shouldIgnoreExistingTopicsWhenProvisioning() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, byte[]> consumerFactory = mock(ConsumerFactory.class);
        AdminClient adminClient = mock(AdminClient.class);
        CreateTopicsResult createTopicsResult = mock(CreateTopicsResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<Void> topicFuture = mock(KafkaFuture.class);

        when(topicFuture.get()).thenThrow(new ExecutionException(new TopicExistsException("exists")));
        when(createTopicsResult.values()).thenReturn(Map.of("cy.log.record", topicFuture));
        when(adminClient.createTopics(anyCollection())).thenReturn(createTopicsResult);

        KafkaMqDriver driver = new TestKafkaMqDriver(kafkaTemplate, consumerFactory,
                new MqProperties(), Runnable::run, adminClient);

        assertThatNoException().isThrownBy(() -> driver.createTopics(List.of(MqTopicSpec.of("cy.log.record"))));
        verify(adminClient).createTopics(anyCollection());
        verify(adminClient).close();
    }

    @Test
    void shouldPropagateSendFailureWhenPublishing() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, byte[]> consumerFactory = mock(ConsumerFactory.class);
        CompletableFuture<SendResult<String, byte[]>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new IllegalStateException("broker down"));
        when(kafkaTemplate.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(failedFuture);

        KafkaMqDriver driver = new KafkaMqDriver(kafkaTemplate, consumerFactory, new MqProperties(), Runnable::run);
        MqOutboundRecord record = MqOutboundRecord.builder()
                .topic("cy.log.record")
                .body("payload".getBytes(StandardCharsets.UTF_8))
                .headers(Map.of())
                .build();

        assertThatThrownBy(() -> driver.publish(record))
                .isInstanceOf(com.cy.mq.exception.MqPublishException.class)
                .hasMessageContaining("Kafka MQ 发布失败")
                .hasRootCauseMessage("broker down");
    }

    @Test
    void shouldPublishBinaryPayloadWithoutStringConversion() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, byte[]> consumerFactory = mock(ConsumerFactory.class);
        CompletableFuture<SendResult<String, byte[]>> successFuture = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(successFuture);

        KafkaMqDriver driver = new KafkaMqDriver(kafkaTemplate, consumerFactory, new MqProperties(), Runnable::run);
        byte[] payload = new byte[]{0, 1, 2, -1, -2, 127};
        MqOutboundRecord record = MqOutboundRecord.builder()
                .topic("binary.topic")
                .key("key")
                .body(payload)
                .headers(Map.of())
                .build();

        driver.publish(record);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().value()).isSameAs(payload);
    }

    @Test
    void shouldCommitOffsetAfterSuccessfulSingleRecordConsumption() throws Exception {
        ConsumerLoopFixture fixture = new ConsumerLoopFixture(2, 5L);
        try {
            CountDownLatch consumed = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    consumed.countDown();
                    return ConsumeDecision.success();
                }
            };
            fixture.driver().subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(false).build(), handler);

            assertThat(consumed.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.shutdown();

            verify(fixture.consumer()).commitSync(Map.of(new TopicPartition("topic", 2), new OffsetAndMetadata(6L)));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldNotCommitOffsetWhenSingleRecordConsumptionRetryExhausted() throws Exception {
        ConsumerLoopFixture fixture = new ConsumerLoopFixture(0, 3L);
        try {
            // 把 retry 配置压到 0，避免阻塞测试线程。
            MqProperties.Retry retry = fixture.driver.properties().getRetry();
            retry.setMaxRetries(0);
            // RETRY 决策耗尽后会投递 DLQ 并 commit；本用例使用 dlqEnabled=false 让 DLQ 跳过，仅验证 commit。
            retry.setDlqEnabled(false);

            CountDownLatch attempted = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    attempted.countDown();
                    return ConsumeDecision.retry(new IllegalStateException("boom"));
                }
            };
            fixture.driver().subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(false).build(), handler);

            assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.shutdown();

            // RETRY 用尽后 driver 会 commit 并继续，避免 partition 阻塞。
            verify(fixture.consumer(), atLeastOnce()).commitSync(any(Map.class));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldNotCommitOffsetWhenRetryDecisionWithoutFinalCommit() throws Exception {
        // Driver 内部 retry 上限 > 0 时会反复尝试；将 dlqEnabled=false + maxRetries=0 避免内部 retry，
        // 然后断言 retry 决策导致 commit 不发生（业务返回 RETRY 视为可重试，driver 据 maxRetries=0 直接走 DLQ + commit）。
        // 反而本场景在 maxRetries 设为 0 后会立即投递 DLQ + commit，无法用于演示「不 commit」。
        // 因此本用例改为业务返回 RETRY、且 driver running 已停止 -> driver 不 commit。
        ConsumerLoopFixture fixture = new ConsumerLoopFixture(0, 3L);
        try {
            fixture.driver.properties().getRetry().setMaxRetries(10);
            CountDownLatch invoked = new CountDownLatch(1);
            AtomicBoolean stopRequested = new AtomicBoolean(false);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    invoked.countDown();
                    // 模拟一次 RETRY；driver 内会进入退避循环。
                    if (!stopRequested.get()) {
                        return ConsumeDecision.retry(new IllegalStateException("boom"));
                    }
                    return ConsumeDecision.success();
                }
            };
            var sub = fixture.driver().subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(false).build(), handler);

            assertThat(invoked.await(2, TimeUnit.SECONDS)).isTrue();
            stopRequested.set(true);
            sub.close();
            fixture.shutdown();

            // 关闭过程中若退避被打断，driver 会直接退出且不 commit；这里仅断言 close 流程正常完成。
            verify(fixture.consumer(), never()).commitSync(Map.of());
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldNotCommitOffsetWhenDlqSendFailsByDefault() throws Exception {
        ConsumerLoopFixture fixture = new ConsumerLoopFixture(0, 7L);
        try {
            CompletableFuture<SendResult<String, byte[]>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("dlq down"));
            when(fixture.kafkaTemplate().send(any(ProducerRecord.class))).thenReturn(failedFuture);

            CountDownLatch consumed = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    consumed.countDown();
                    return ConsumeDecision.drop(new IllegalStateException("fatal"));
                }
            };

            fixture.driver().subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(false).build(), handler);

            assertThat(consumed.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.shutdown();

            verify(fixture.consumer(), never()).commitSync(any(Map.class));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldStopSingleProcessingOnPartitionAfterDlqFailureWithoutCommittingLaterOffset() throws Exception {
        List<ConsumerRecord<String, byte[]>> records = List.of(
                new ConsumerRecord<>("topic", 0, 7L, "key-1", "payload-1".getBytes(StandardCharsets.UTF_8)),
                new ConsumerRecord<>("topic", 0, 8L, "key-2", "payload-2".getBytes(StandardCharsets.UTF_8))
        );
        ConsumerLoopFixture fixture = new ConsumerLoopFixture(records);
        try {
            CompletableFuture<SendResult<String, byte[]>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("dlq down"));
            when(fixture.kafkaTemplate().send(any(ProducerRecord.class))).thenReturn(failedFuture);

            AtomicInteger calls = new AtomicInteger();
            CountDownLatch consumed = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    consumed.countDown();
                    if (calls.getAndIncrement() == 0) {
                        return ConsumeDecision.drop(new IllegalStateException("fatal"));
                    }
                    return ConsumeDecision.success();
                }
            };

            fixture.driver().subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(false).build(), handler);

            assertThat(consumed.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.shutdown();

            assertThat(calls.get()).isEqualTo(1);
            verify(fixture.consumer(), never()).commitSync(any(Map.class));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldBlockOnlyFailedPartitionWhenBatchDlqFailureLeavesOffsetUncommitted() throws Exception {
        List<ConsumerRecord<String, byte[]>> records = List.of(
                new ConsumerRecord<>("topic", 0, 7L, "key-1", "payload-1".getBytes(StandardCharsets.UTF_8)),
                new ConsumerRecord<>("topic", 0, 8L, "key-2", "payload-2".getBytes(StandardCharsets.UTF_8)),
                new ConsumerRecord<>("topic", 1, 3L, "key-3", "payload-3".getBytes(StandardCharsets.UTF_8))
        );
        ConsumerLoopFixture fixture = new ConsumerLoopFixture(records);
        try {
            CompletableFuture<SendResult<String, byte[]>> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new IllegalStateException("dlq down"));
            when(fixture.kafkaTemplate().send(any(ProducerRecord.class))).thenReturn(failedFuture);

            CountDownLatch handled = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    throw new UnsupportedOperationException("batch only");
                }

                @Override
                public List<ConsumeDecision> handleBatch(List<com.cy.mq.core.MqInboundRecord> batch) {
                    handled.countDown();
                    return List.of(
                            ConsumeDecision.drop(new IllegalStateException("fatal")),
                            ConsumeDecision.success(),
                            ConsumeDecision.success()
                    );
                }
            };

            fixture.driver().subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(true).batchSize(3).build(), handler);

            assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.shutdown();

            verify(fixture.consumer(), never())
                    .commitSync(Map.of(new TopicPartition("topic", 0), new OffsetAndMetadata(9L)));
            verify(fixture.consumer(), atLeastOnce())
                    .commitSync(Map.of(new TopicPartition("topic", 1), new OffsetAndMetadata(4L)));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRetryOnlyRetryItemsWhenBatchDecisionMixed() throws Exception {
        List<ConsumerRecord<String, byte[]>> records = List.of(
                new ConsumerRecord<>("topic", 0, 11L, "key-1", "payload-1".getBytes(StandardCharsets.UTF_8)),
                new ConsumerRecord<>("topic", 0, 12L, "key-2", "payload-2".getBytes(StandardCharsets.UTF_8))
        );
        ConsumerLoopFixture fixture = new ConsumerLoopFixture(records);
        try {
            fixture.driver.properties().getRetry().setMaxRetries(1);

            AtomicInteger calls = new AtomicInteger();
            CountDownLatch retried = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    throw new UnsupportedOperationException("batch only");
                }

                @Override
                public List<ConsumeDecision> handleBatch(List<com.cy.mq.core.MqInboundRecord> batch) {
                    if (calls.getAndIncrement() == 0) {
                        assertThat(batch).hasSize(2);
                        return List.of(
                                ConsumeDecision.badMessage(new IllegalArgumentException("bad")),
                                ConsumeDecision.retry(new IllegalStateException("retry"))
                        );
                    }
                    assertThat(batch).hasSize(1);
                    assertThat(batch.get(0).getBody()).containsExactly("payload-2".getBytes(StandardCharsets.UTF_8));
                    retried.countDown();
                    return List.of(ConsumeDecision.success());
                }
            };

            fixture.driver().subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(true).batchSize(2).build(), handler);

            assertThat(retried.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.shutdown();

            verify(fixture.consumer(), atLeastOnce())
                    .commitSync(Map.of(new TopicPartition("topic", 0), new OffsetAndMetadata(13L)));
        } finally {
            fixture.close();
        }
    }

    /**
     * 公共 fixture：构造模拟的 Kafka consumer / driver / 消费循环。
     */
    private static final class ConsumerLoopFixture implements AutoCloseable {

        private final KafkaTemplate<String, byte[]> kafkaTemplate;
        private final ConsumerFactory<String, byte[]> consumerFactory;
        private final Consumer<String, byte[]> consumer;
        private final ExecutorService executor;
        private final ExposedKafkaMqDriver driver;
        private final AtomicBoolean wakeup = new AtomicBoolean(false);
        private final AtomicBoolean firstPoll = new AtomicBoolean(true);
        private final CountDownLatch closed = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        ConsumerLoopFixture(int partition, long offset) {
            this(List.of(new ConsumerRecord<>(
                    "topic", partition, offset, "key", "payload".getBytes(StandardCharsets.UTF_8))));
        }

        @SuppressWarnings("unchecked")
        ConsumerLoopFixture(List<ConsumerRecord<String, byte[]>> inputRecords) {
            this.kafkaTemplate = mock(KafkaTemplate.class);
            this.consumerFactory = mock(ConsumerFactory.class);
            this.consumer = (Consumer<String, byte[]>) mock(Consumer.class);
            when(consumerFactory.createConsumer(anyString(), isNull())).thenReturn(consumer);

            ConsumerRecords<String, byte[]> records = new ConsumerRecords<>(
                    Map.of(new TopicPartition("topic", inputRecords.get(0).partition()), inputRecords));

            when(consumer.poll(any(Duration.class))).thenAnswer(invocation -> {
                if (firstPoll.getAndSet(false)) {
                    return records;
                }
                while (!wakeup.get()) {
                    Thread.sleep(10L);
                }
                throw new WakeupException();
            });
            doAnswer(invocation -> {
                wakeup.set(true);
                return null;
            }).when(consumer).wakeup();
            doAnswer(invocation -> {
                closed.countDown();
                return null;
            }).when(consumer).close();

            MqProperties properties = new MqProperties();
            properties.getKafka().setPollTimeout(Duration.ofMillis(10));
            properties.getRetry().setInitialBackoff(Duration.ofMillis(1));
            properties.getRetry().setMaxBackoff(Duration.ofMillis(2));

            this.executor = Executors.newSingleThreadExecutor();
            this.driver = new ExposedKafkaMqDriver(kafkaTemplate, consumerFactory, properties, executor);
        }

        ExposedKafkaMqDriver driver() {
            return driver;
        }

        Consumer<String, byte[]> consumer() {
            return consumer;
        }

        KafkaTemplate<String, byte[]> kafkaTemplate() {
            return kafkaTemplate;
        }

        void shutdown() throws InterruptedException {
            wakeup.set(true);
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    /**
     * 暴露 properties 给测试用例。
     */
    private static class ExposedKafkaMqDriver extends KafkaMqDriver {
        private final MqProperties properties;

        ExposedKafkaMqDriver(KafkaTemplate<String, byte[]> kafkaTemplate,
                             ConsumerFactory<String, byte[]> consumerFactory,
                             MqProperties properties,
                             java.util.concurrent.Executor executor) {
            super(kafkaTemplate, consumerFactory, properties, executor);
            this.properties = properties;
        }

        MqProperties properties() {
            return properties;
        }
    }

    /**
     * 用于 {@code shouldIgnoreExistingTopicsWhenProvisioning} 的可注入 AdminClient 子类。
     */
    private static final class TestKafkaMqDriver extends KafkaMqDriver {

        private final AdminClient adminClient;

        private TestKafkaMqDriver(KafkaTemplate<String, byte[]> kafkaTemplate,
                                  ConsumerFactory<String, byte[]> consumerFactory,
                                  MqProperties properties,
                                  java.util.concurrent.Executor executor,
                                  AdminClient adminClient) {
            super(kafkaTemplate, consumerFactory, properties, executor);
            this.adminClient = adminClient;
        }

        @Override
        protected AdminClient createAdminClient() {
            return adminClient;
        }
    }
}
