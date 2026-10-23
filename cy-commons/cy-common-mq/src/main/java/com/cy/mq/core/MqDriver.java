package com.cy.mq.core;

/**
 * 消息队列驱动。
 * <p>
 * driver 是 MQ 组件最底层的抽象，<b>只</b>负责底层 broker IO 与位点 / ACK 管理：
 * </p>
 * <ul>
 *     <li><b>发布</b>：把已经被 codec 编码好的 {@link MqOutboundRecord} 写入 broker。</li>
 *     <li><b>订阅</b>：从 broker 拉取消息，构造 {@link MqInboundRecord} 调用
 *     {@link MqRecordHandler}，并据 handler 返回的 {@link ConsumeDecision} 处理 ACK / 重投 / 死信。</li>
 *     <li><b>主题</b>：可选的主题创建能力。</li>
 * </ul>
 * <p>
 * driver 不接触业务 payload 类型，不调用业务监听器，也不参与链路追踪 —— 这些是
 * {@code com.cy.mq.runtime} 层的职责。这种纯 broker IO 的设计让外部系统消息、
 * 内部消息、跨语言消息可以在同一套 driver 实现下被统一处理。
 * </p>
 *
 * @author pcy
 */
public interface MqDriver {

    /**
     * 驱动类型。
     *
     * @return 驱动类型
     */
    MqDriverType type();

    /**
     * 发布消息。
     * <p>
     * 实现要点：
     * </p>
     * <ul>
     *     <li>使用 {@link MqOutboundRecord#getBody()} 作为业务 payload 字节，写入 broker 的 body 通道。</li>
     *     <li>把 {@link MqOutboundRecord#getHeaders()} 写入 broker 的原生头部通道；不支持头部的 broker
     *     可以选择性地丢弃或仅保留 framework 必需头部。</li>
     *     <li>不要修改、不要重新序列化 body。</li>
     * </ul>
     *
     * @param record 出站记录
     */
    void publish(MqOutboundRecord record);

    /**
     * 订阅消息。
     * <p>
     * 实现要点：
     * </p>
     * <ul>
     *     <li>从 broker 拉到原生记录后，构造 {@link MqInboundRecord} 调用 handler；
     *     需注意把 body 与 headers 严格分离，不要把 framework 头部混进 body。</li>
     *     <li>据 handler 返回的 {@link ConsumeDecision#shouldAck()} 决定是否提交位点 / ACK。</li>
     *     <li>{@link MqSubscribeSpec#isBatch()} 为 {@code true} 时，应优先调用
     *     {@link MqRecordHandler#handleBatch(java.util.List)}；不支持批量的 driver 可逐条调用 {@link MqRecordHandler#handle(MqInboundRecord)}。</li>
     * </ul>
     *
     * @param spec    订阅参数
     * @param handler 入站记录处理器
     * @return 订阅句柄，关闭以取消订阅
     */
    MqSubscription subscribe(MqSubscribeSpec spec, MqRecordHandler handler);

    /**
     * 创建主题。
     * <p>
     * 默认实现为空，由具体驱动按需覆盖；不支持主题管理的驱动可保持 no-op。
     * </p>
     *
     * @param topics 主题定义
     */
    default void createTopics(Iterable<MqTopicSpec> topics) {
    }
}
