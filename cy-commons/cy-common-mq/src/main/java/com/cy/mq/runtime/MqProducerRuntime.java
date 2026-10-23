package com.cy.mq.runtime;

import com.cy.mq.codec.MqMessageCodec;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqMetadata;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqPublishRequest;
import com.cy.mq.exception.MqPublishException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;

/**
 * MQ 发布运行时。
 * <p>
 * 统一处理一次消息发布的全过程：
 * </p>
 * <ol>
 *     <li>路由 driver。</li>
 *     <li>构造 {@link MqMetadata}：生成 messageId、时间戳，捕获当前 trace 上下文。</li>
 *     <li>调用 {@link MqMessageCodec} 把 metadata + payload 编码为 {@link MqOutboundRecord}。</li>
 *     <li>调用 driver 写入底层 broker。</li>
 * </ol>
 * <p>
 * driver 在 publish 阶段不再做 payload 序列化或 metadata 拼装，仅负责 IO。
 * </p>
 *
 * @author pcy
 */
@Slf4j
@RequiredArgsConstructor
public class MqProducerRuntime {

    /**
     * driver 注册中心。
     */
    private final MqDriverRegistry driverRegistry;

    /**
     * 消息编解码器。
     */
    private final MqMessageCodec messageCodec;

    /**
     * 发布消息。
     *
     * @param request 发布请求
     * @throws MqPublishException 发布失败
     */
    public void publish(MqPublishRequest request) {
        validate(request);

        // 1. 路由 driver。
        MqDriverType requestedDriver = request.getDriver();
        MqDriver driver = driverRegistry.getDriver(requestedDriver);

        // 2. 构造 metadata：messageId / timestamp 由 newOutbound 生成；trace 由 MqTraceSupport 捕获。
        MqMetadata metadata = MqMetadata.newOutbound(request.getTopic(), request.getKey());
        metadata.setDeliveryMode(request.getDeliveryMode());
        if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
            metadata.setHeaders(new LinkedHashMap<>(request.getHeaders()));
        }
        MqTraceSupport.captureInto(metadata);

        // 3. 编码为出站记录。
        MqOutboundRecord record;
        try {
            record = messageCodec.encode(metadata, request.getPayload());
            record = record.toBuilder()
                    .driver(driver.type())
                    .build();
        } catch (Exception e) {
            throw new MqPublishException(request.getTopic(), metadata.getMessageId(),
                    "MQ payload 编码失败", e);
        }

        // 4. 调用 driver 写入 broker。所有非 MqPublishException 异常统一包装。
        try {
            driver.publish(record);
        } catch (MqPublishException e) {
            throw e;
        } catch (Exception e) {
            throw new MqPublishException(request.getTopic(), metadata.getMessageId(),
                    "driver 发布失败: driver=" + driver.type(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("MQ 消息发布完成: driver={}, topic={}, messageId={}, traceId={}",
                    driver.type(), metadata.getTopic(), metadata.getMessageId(), metadata.getTraceId());
        }
    }

    /**
     * 校验发布请求。
     *
     * @param request 发布请求
     */
    private static void validate(MqPublishRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("MqPublishRequest must not be null");
        }
        if (request.getTopic() == null || request.getTopic().isBlank()) {
            throw new IllegalArgumentException("MqPublishRequest.topic must not be blank");
        }
    }
}
