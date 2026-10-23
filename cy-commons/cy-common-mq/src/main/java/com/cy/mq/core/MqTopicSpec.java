package com.cy.mq.core;

import java.util.Map;

/**
 * 消息主题定义。
 * <p>
 * 用于描述主题名称、分区、副本及附加配置，供具备主题管理能力的驱动创建主题时使用。
 * </p>
 *
 * @param name       主题名
 * @param partitions 分区数
 * @param replicas   副本数
 * @param configs    主题配置
 * @author pcy
 */
public record MqTopicSpec(String name, Integer partitions, Short replicas, Map<String, String> configs) {

    /**
     * 规范化主题配置。
     */
    public MqTopicSpec {
        configs = configs == null || configs.isEmpty() ? Map.of() : Map.copyOf(configs);
    }

    /**
     * 创建仅包含主题名的定义。
     *
     * @param name 主题名
     * @return 主题定义
     */
    public static MqTopicSpec of(String name) {
        return new MqTopicSpec(name, null, null, Map.of());
    }
}
