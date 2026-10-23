package com.cy.mq.runtime;

import com.cy.mq.codec.MqMessageCodec;
import com.cy.mq.core.ConsumeDecision;
import com.cy.mq.core.MqBatchConsumer;
import com.cy.mq.core.MqConsumer;
import com.cy.mq.core.MqInboundRecord;
import com.cy.mq.core.MqMessage;
import com.cy.mq.core.MqMetadata;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.exception.MqConsumeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MQ 消费运行时。
 * <p>
 * 提供把业务 {@link MqConsumer} / {@link MqBatchConsumer} 包装为 driver
 * 可识别的 {@link MqRecordHandler} 的能力。包装后的 handler 负责：
 * </p>
 * <ol>
 *     <li>调用 {@link MqMessageCodec} 把 {@link MqInboundRecord} 解码为 {@link MqMessage}。</li>
 *     <li>建立 trace 作用域：还原 traceId / 生成新 spanId / 同步 MDC + InvocationContext。</li>
 *     <li>调用业务监听器。</li>
 *     <li>据正常返回 / 异常类型映射为 {@link ConsumeDecision}：<br>
 *     正常 → SUCCESS；解码失败 → BAD_MESSAGE；
 *     {@link MqConsumeException} 不可重试 → DROP；其他异常 → RETRY。</li>
 *     <li>关闭 trace 作用域，还原原线程上下文。</li>
 * </ol>
 *
 * @author pcy
 */
@Slf4j
@RequiredArgsConstructor
public class MqConsumerRuntime {

    /**
     * 消息编解码器。
     */
    private final MqMessageCodec messageCodec;

    /**
     * 创建单条 record 处理器。
     *
     * @param payloadType 业务 payload 类型
     * @param consumer    业务回调
     * @param <T>         payload 类型
     * @return 包装后的 record 处理器
     */
    public <T> MqRecordHandler newSingleHandler(Class<T> payloadType, MqConsumer<T> consumer) {
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType must not be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer must not be null");
        }
        return new SingleHandler<>(messageCodec, payloadType, consumer);
    }

    /**
     * 创建批量 record 处理器。
     *
     * @param payloadType 业务 payload 类型
     * @param consumer    批量业务回调
     * @param <T>         payload 类型
     * @return 包装后的 record 处理器
     */
    public <T> MqRecordHandler newBatchHandler(Class<T> payloadType, MqBatchConsumer<T> consumer) {
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType must not be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer must not be null");
        }
        return new BatchHandler<>(messageCodec, payloadType, consumer);
    }

    /**
     * 把业务异常映射为 {@link ConsumeDecision}。
     */
    static ConsumeDecision mapBusinessException(Throwable error) {
        if (error instanceof MqConsumeException ce) {
            return ce.isRetryable() ? ConsumeDecision.retry(ce) : ConsumeDecision.drop(ce);
        }
        return ConsumeDecision.retry(error);
    }

    // ============== 单条处理器 ==============

    /**
     * 单条消息处理器。
     */
    private record SingleHandler<T>(MqMessageCodec codec, Class<T> payloadType, MqConsumer<T> consumer)
            implements MqRecordHandler {

        @Override
        public ConsumeDecision handle(MqInboundRecord record) {
            // 1. 解码：失败视为坏消息。
            MqMessage<T> message;
            try {
                message = codec.decode(record, payloadType);
            } catch (Exception e) {
                log.error("MQ 消息解码失败: driver={}, topic={}, nativeId={}",
                        record.getDriver(), record.getTopic(), record.getNativeMessageId(), e);
                return ConsumeDecision.badMessage(e);
            }

            // 2. 建立 trace 作用域，调用业务回调。
            MqMetadata metadata = message.metadata();
            try (MqTraceSupport.TraceScope ignored = MqTraceSupport.establish(metadata)) {
                consumer.accept(message.payload(), metadata);
                return ConsumeDecision.success();
            } catch (Exception e) {
                log.error("MQ 业务消费失败: driver={}, topic={}, messageId={}",
                        record.getDriver(), record.getTopic(), metadata.getMessageId(), e);
                return mapBusinessException(e);
            }
        }
    }

    // ============== 批量处理器 ==============

    /**
     * 批量消息处理器。
     * <p>
     * 注意：当 driver 没有批量能力（如 Redis Pub/Sub）而又被注册为批量订阅时，
     * driver 仍可能逐条调用 {@link #handle(MqInboundRecord)}；本实现会把单条
     * 转换为长度为 1 的批量回调，保持业务监听器签名一致。
     * </p>
     */
    @RequiredArgsConstructor
    private static class BatchHandler<T> implements MqRecordHandler {

        private final MqMessageCodec codec;
        private final Class<T> payloadType;
        private final MqBatchConsumer<T> consumer;

        @Override
        public ConsumeDecision handle(MqInboundRecord record) {
            List<ConsumeDecision> results = handleBatch(Collections.singletonList(record));
            return results.isEmpty() ? ConsumeDecision.success() : results.get(0);
        }

        @Override
        public List<ConsumeDecision> handleBatch(List<MqInboundRecord> records) {
            if (records == null || records.isEmpty()) {
                return Collections.emptyList();
            }

            // 1. 全部解码：失败的位置直接标记 BAD_MESSAGE，剩余有效消息进入批量回调。
            int size = records.size();
            List<MqMessage<T>> messages = new ArrayList<>(size);
            List<ConsumeDecision> results = new ArrayList<>(size);
            List<Integer> validIndices = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                MqInboundRecord record = records.get(i);
                try {
                    messages.add(codec.decode(record, payloadType));
                    results.add(null);
                    validIndices.add(i);
                } catch (Exception e) {
                    log.error("MQ 消息解码失败: driver={}, topic={}, nativeId={}",
                            record.getDriver(), record.getTopic(), record.getNativeMessageId(), e);
                    messages.add(null);
                    results.add(ConsumeDecision.badMessage(e));
                }
            }

            if (validIndices.isEmpty()) {
                return results;
            }

            // 2. 抽取有效 payloads / metadata。
            List<T> payloads = new ArrayList<>(validIndices.size());
            List<MqMetadata> metadataList = new ArrayList<>(validIndices.size());
            for (Integer idx : validIndices) {
                MqMessage<T> message = messages.get(idx);
                payloads.add(message.payload());
                metadataList.add(message.metadata());
            }

            // 3. trace 作用域以批中第一条消息为基准；调用批量回调。
            ConsumeDecision batchDecision;
            MqMetadata firstMetadata = metadataList.get(0);
            try (MqTraceSupport.TraceScope ignored = MqTraceSupport.establish(firstMetadata)) {
                consumer.accept(payloads, metadataList);
                batchDecision = ConsumeDecision.success();
            } catch (Exception e) {
                log.error("MQ 批量业务消费失败: topic={}, batchSize={}",
                        firstMetadata.getTopic(), payloads.size(), e);
                batchDecision = mapBusinessException(e);
            }

            // 4. 把批量决策应用到所有有效消息位。
            for (Integer idx : validIndices) {
                results.set(idx, batchDecision);
            }
            return results;
        }
    }
}
