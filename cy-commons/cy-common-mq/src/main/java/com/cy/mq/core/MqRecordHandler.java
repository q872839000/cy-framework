package com.cy.mq.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 入站原始记录处理器。
 * <p>
 * 这是 driver 与消费 runtime 之间的唯一桥梁。driver 拉到原始消息后
 * 构造 {@link MqInboundRecord}，调用本接口的相应方法把记录交给
 * runtime；runtime 完成 decode、trace 恢复、业务回调，并返回
 * {@link ConsumeDecision}。driver 据此处理 ACK / commit / 重投。
 * </p>
 * <p>
 * <b>设计原则：</b>driver 不直接接触业务 consumer，也不做 payload 反序列化，
 * 这些都是 runtime 的职责；driver 只负责 broker IO 与位点管理。
 * </p>
 *
 * @author pcy
 */
public interface MqRecordHandler {

    /**
     * 处理单条入站记录。
     * <p>
     * 实现方应保证不抛出异常：所有错误都封装到 {@link ConsumeDecision} 返回。
     * 这样 driver 的消费循环逻辑可以保持简单，不必处理意外异常。
     * </p>
     *
     * @param record 入站记录
     * @return 消费决策
     */
    ConsumeDecision handle(MqInboundRecord record);

    /**
     * 处理批量入站记录。
     * <p>
     * 默认实现：逐条调用 {@link #handle(MqInboundRecord)}。具体 runtime 可重写
     * 以支持真正的批量监听（一次性把整批 payload 交给业务方）。
     * </p>
     *
     * @param records 一批入站记录，按 broker 顺序排列
     * @return 与 records 等长的决策列表，索引按 records 顺序对应
     */
    default List<ConsumeDecision> handleBatch(List<MqInboundRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<ConsumeDecision> results = new ArrayList<>(records.size());
        for (MqInboundRecord record : records) {
            results.add(handle(record));
        }
        return results;
    }
}
