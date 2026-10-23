package com.cy.mq.driver.redis;

import com.cy.mq.config.MqProperties;
import com.cy.mq.core.ConsumeDecision;
import com.cy.mq.core.MqDeliveryMode;
import com.cy.mq.core.MqHeaders;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqSubscription;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
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
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisMqDriverTest {

    @Test
    void shouldPublishPubSubPayloadAsRawBytes() {
        RedisConnection connection = mock(RedisConnection.class);
        doReturn(1L).when(connection).publish(any(byte[].class), any(byte[].class));
        RedisMqDriver driver = newDriver(connection, new MqProperties(), Runnable::run);
        byte[] payload = new byte[]{0, 1, 2, -1, -2, 127};

        driver.publish(MqOutboundRecord.builder()
                .topic("binary.pubsub")
                .deliveryMode(MqDeliveryMode.PUBSUB)
                .body(payload)
                .headers(Map.of())
                .build());

        ArgumentCaptor<byte[]> channelCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(connection).publish(channelCaptor.capture(), payloadCaptor.capture());
        assertThat(new String(channelCaptor.getValue(), StandardCharsets.UTF_8)).isEqualTo("binary.pubsub");
        assertThat(payloadCaptor.getValue()).containsExactly(payload);
    }

    @Test
    void shouldPublishStreamPayloadAsRawBytes() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands commands = mock(RedisStreamCommands.class);
        doReturn(commands).when(connection).streamCommands();
        doReturn(RecordId.of("1-0")).when(commands).xAdd(any(byte[].class), any(Map.class));
        RedisMqDriver driver = newDriver(connection, new MqProperties(), Runnable::run);
        byte[] payload = new byte[]{0, 1, 2, -1, -2, 127};

        driver.publish(MqOutboundRecord.builder()
                .topic("binary.stream")
                .deliveryMode(MqDeliveryMode.STREAM)
                .body(payload)
                .headers(Map.of(MqHeaders.MESSAGE_ID, "mid-1", MqHeaders.KEY, "key-1"))
                .build());

        ArgumentCaptor<byte[]> streamCaptor = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<byte[], byte[]>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(commands).xAdd(streamCaptor.capture(), fieldsCaptor.capture());
        assertThat(new String(streamCaptor.getValue(), StandardCharsets.UTF_8)).isEqualTo("binary.stream");
        assertThat(field(fieldsCaptor.getValue(), MqHeaders.PAYLOAD_FIELD)).containsExactly(payload);
        assertThat(new String(field(fieldsCaptor.getValue(), MqHeaders.MESSAGE_ID), StandardCharsets.UTF_8)).isEqualTo("mid-1");
    }

    @Test
    void shouldFailFastWhenConsumerGroupCreationFails() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands commands = mock(RedisStreamCommands.class);
        doReturn(commands).when(connection).streamCommands();
        doThrow(new RuntimeException("NOAUTH Authentication required"))
                .when(commands).xGroupCreate(any(byte[].class), eq("group"), any(ReadOffset.class), eq(true));
        RedisMqDriver driver = newDriver(connection, new MqProperties(), Runnable::run);

        assertThatThrownBy(() -> driver.subscribe(MqSubscribeSpec.builder()
                .topic("topic").groupId("group").build(), new MqRecordHandler() {
                    @Override
                    public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                        return ConsumeDecision.success();
                    }
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis Stream 创建消费组失败");
    }

    @Test
    void shouldSendDropMessageToDlqThenAck() throws Exception {
        StreamFixture fixture = new StreamFixture();
        try {
            CountDownLatch handled = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    handled.countDown();
                    assertThat(record.getBody()).containsExactly(fixture.payload);
                    return ConsumeDecision.drop(new IllegalStateException("fatal"));
                }
            };
            MqSubscription subscription = fixture.driver.subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(false).build(), handler);

            assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.dlqWritten.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.acked.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.close();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<byte[], byte[]>> dlqFieldsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(fixture.commands).xAdd(eq("topic.DLQ".getBytes(StandardCharsets.UTF_8)), dlqFieldsCaptor.capture());
            assertThat(field(dlqFieldsCaptor.getValue(), MqHeaders.PAYLOAD_FIELD)).containsExactly(fixture.payload);
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldKeepDropMessagePendingWhenDlqWriteFailsByDefault() throws Exception {
        StreamFixture fixture = new StreamFixture();
        try {
            fixture.failDlq.set(true);
            CountDownLatch handled = new CountDownLatch(1);
            MqRecordHandler handler = new MqRecordHandler() {
                @Override
                public ConsumeDecision handle(com.cy.mq.core.MqInboundRecord record) {
                    handled.countDown();
                    return ConsumeDecision.drop(new IllegalStateException("fatal"));
                }
            };
            MqSubscription subscription = fixture.driver.subscribe(MqSubscribeSpec.builder()
                    .topic("topic").groupId("group").batch(false).build(), handler);

            assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.dlqAttempted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50L);
            subscription.close();

            verify(fixture.commands, never()).xAck(any(byte[].class), anyString(), any(RecordId[].class));
        } finally {
            fixture.close();
        }
    }

    /**
     * Regression for the Redisson 3.31.0 {@code RedissonStreamCommands.xClaim} bug where the
     * adapter mistakenly read {@code XClaimOptions.getIdleTime()} (the optional {@code IDLE}
     * modifier) as the mandatory min-idle threshold and threw NPE for callers that only set
     * {@code minIdle()}. This test documents our contract on the Spring Data Redis facade:
     * {@code claimAndConsume} must populate {@code minIdle} and {@code ids} only, never the
     * optional {@code idle} modifier. Combined with Redisson 3.40.0+ (the upstream fix) the
     * Redis Stream PEL reclaim path then works end-to-end.
     */
    @Test
    void shouldClaimPendingMessagesUsingMinIdleOnlyAndRehandle() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStreamCommands commands = mock(RedisStreamCommands.class);
        doReturn(commands).when(connection).streamCommands();
        doReturn("OK").when(commands).xGroupCreate(any(byte[].class), eq("group"),
                any(ReadOffset.class), eq(true));
        doReturn(List.<ByteRecord>of()).when(commands).xReadGroup(any(Consumer.class),
                any(StreamReadOptions.class), any(StreamOffset[].class));

        Duration pendingTimeout = Duration.ofSeconds(30);
        PendingMessage stale = new PendingMessage(
                RecordId.of("9-0"),
                Consumer.from("group", "stale-consumer"),
                pendingTimeout.plusSeconds(30),
                1L);
        AtomicBoolean firstPendingScan = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstPendingScan.getAndSet(false)) {
                return new PendingMessages("group", List.of(stale));
            }
            return new PendingMessages("group", Range.unbounded(), List.of());
        }).when(commands).xPending(any(byte[].class), eq("group"), any(XPendingOptions.class));

        byte[] payload = new byte[]{0, 1, 2, -1, -2, 127};
        AtomicReference<XClaimOptions> capturedOptions = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedOptions.set(invocation.getArgument(3));
            return List.of(byteRecord("topic", "9-0", payload));
        }).when(commands).xClaim(any(byte[].class), eq("group"), anyString(), any(XClaimOptions.class));

        CountDownLatch acked = new CountDownLatch(1);
        doAnswer(invocation -> {
            acked.countDown();
            return 1L;
        }).when(commands).xAck(any(byte[].class), eq("group"), any(RecordId[].class));

        MqProperties properties = new MqProperties();
        properties.getRedis().setPollTimeout(Duration.ofMillis(10));
        properties.getRetry().setPendingTimeout(pendingTimeout);
        properties.getRetry().setPendingScanInterval(Duration.ZERO);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        RedisMqDriver driver = new RedisMqDriver(new TestStringRedisTemplate(connection),
                mock(RedisMessageListenerContainer.class), properties, executor);

        CountDownLatch claimedHandled = new CountDownLatch(1);
        MqSubscription subscription = driver.subscribe(MqSubscribeSpec.builder()
                .topic("topic").groupId("group").batch(false).build(), record -> {
                    assertThat(record.getBody()).containsExactly(payload);
                    claimedHandled.countDown();
                    return ConsumeDecision.success();
                });
        try {
            assertThat(claimedHandled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(acked.await(2, TimeUnit.SECONDS)).isTrue();

            XClaimOptions opts = capturedOptions.get();
            assertThat(opts).as("xClaim must be invoked once a stale PEL entry is seen").isNotNull();
            assertThat(opts.getMinIdleTime())
                    .as("min-idle-time must be the configured pendingTimeout (mandatory positional arg)")
                    .isEqualTo(pendingTimeout);
            assertThat(opts.getIdleTime())
                    .as("the optional IDLE modifier must remain unset; the Redisson 3.31.0 adapter "
                            + "buggily read it as min-idle-time and threw NPE")
                    .isNull();
            assertThat(opts.getIds()).extracting(RecordId::getValue).containsExactly("9-0");
        } finally {
            subscription.close();
            executor.shutdownNow();
        }
    }

    private static RedisMqDriver newDriver(RedisConnection connection,
                                           MqProperties properties,
                                           java.util.concurrent.Executor executor) {
        return new RedisMqDriver(new TestStringRedisTemplate(connection),
                mock(RedisMessageListenerContainer.class), properties, executor);
    }

    private static byte[] field(Map<byte[], byte[]> fields, String name) {
        byte[] key = name.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<byte[], byte[]> entry : fields.entrySet()) {
            if (Arrays.equals(entry.getKey(), key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static ByteRecord byteRecord(String topic, String id, byte[] payload) {
        Map<byte[], byte[]> fields = new LinkedHashMap<>();
        fields.put(MqHeaders.PAYLOAD_FIELD.getBytes(StandardCharsets.UTF_8), payload);
        fields.put(MqHeaders.TIMESTAMP.getBytes(StandardCharsets.UTF_8),
                Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        return StreamRecords.rawBytes(fields)
                .withStreamKey(topic.getBytes(StandardCharsets.UTF_8))
                .withId(RecordId.of(id));
    }

    private static final class TestStringRedisTemplate extends StringRedisTemplate {
        private final RedisConnection connection;

        private TestStringRedisTemplate(RedisConnection connection) {
            this.connection = connection;
        }

        @Override
        public <T> T execute(RedisCallback<T> action) {
            return action.doInRedis(connection);
        }
    }

    private static final class StreamFixture implements AutoCloseable {
        private final byte[] payload = new byte[]{0, 1, 2, -1, -2, 127};
        private final RedisConnection connection = mock(RedisConnection.class);
        private final RedisStreamCommands commands = mock(RedisStreamCommands.class);
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CountDownLatch dlqWritten = new CountDownLatch(1);
        private final CountDownLatch dlqAttempted = new CountDownLatch(1);
        private final CountDownLatch acked = new CountDownLatch(1);
        private final AtomicBoolean firstRead = new AtomicBoolean(true);
        private final AtomicBoolean failDlq = new AtomicBoolean(false);
        private final RedisMqDriver driver;

        private StreamFixture() {
            doReturn(commands).when(connection).streamCommands();
            doReturn("OK").when(commands).xGroupCreate(any(byte[].class), eq("group"), any(ReadOffset.class), eq(true));
            doAnswer(invocation -> {
                if (firstRead.getAndSet(false)) {
                    return List.of(byteRecord("topic", "1-0", payload));
                }
                Thread.sleep(20L);
                return List.of();
            }).when(commands).xReadGroup(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class));
            doAnswer(invocation -> {
                dlqAttempted.countDown();
                if (failDlq.get()) {
                    throw new RuntimeException("dlq down");
                }
                dlqWritten.countDown();
                return RecordId.of("2-0");
            }).when(commands).xAdd(eq("topic.DLQ".getBytes(StandardCharsets.UTF_8)), any(Map.class));
            doAnswer(invocation -> {
                acked.countDown();
                return 1L;
            }).when(commands).xAck(any(byte[].class), eq("group"), any(RecordId[].class));

            MqProperties properties = new MqProperties();
            properties.getRedis().setPollTimeout(Duration.ofMillis(10));
            properties.getRetry().setPendingScanInterval(Duration.ofMinutes(1));
            this.driver = newDriver(connection, properties, executor);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
