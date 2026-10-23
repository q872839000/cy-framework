package com.cy.mq.codec;

import com.cy.mq.core.MqInboundRecord;
import com.cy.mq.core.MqMessage;
import com.cy.mq.core.MqMetadata;
import com.cy.mq.core.MqOutboundRecord;

/**
 * MQ 消息编解码器。
 * <p>
 * 在 driver 之上、业务监听器之下，负责 {@link MqMetadata} + payload 与
 * broker 原始记录（{@link MqInboundRecord} / {@link MqOutboundRecord}）之间的转换。
 * </p>
 * <p>
 * <b>编码（出站）：</b>把业务 metadata 与 payload 拆分为：
 * </p>
 * <ul>
 *     <li>原始字节 body（仅 payload，纯净）。</li>
 *     <li>headers map（framework 保留头 + 业务自定义头）。</li>
 * </ul>
 * <p>
 * <b>解码（入站）：</b>把 broker 原始记录还原为业务消息：
 * </p>
 * <ul>
 *     <li>从 headers 读取 framework 保留头还原 metadata；缺失则据 broker 原生信息合成。</li>
 *     <li>把 body 按目标 payloadType 反序列化。</li>
 *     <li>没有任何 framework 头部时，标记 metadata 为 synthetic，runtime 据此生成新 traceId。</li>
 * </ul>
 *
 * @author pcy
 */
public interface MqMessageCodec {

    /**
     * 编码：把 metadata + payload 封装为出站记录。
     *
     * @param metadata 元数据，必填
     * @param payload  业务 payload（可为 {@code null}）
     * @return 出站记录
     */
    MqOutboundRecord encode(MqMetadata metadata, Object payload);

    /**
     * 解码：把入站记录还原为业务消息。
     *
     * @param record      入站记录
     * @param payloadType 业务 payload 目标类型
     * @param <T>         payload 类型
     * @return 业务消息（metadata 永远非 {@code null}）
     */
    <T> MqMessage<T> decode(MqInboundRecord record, Class<T> payloadType);
}
