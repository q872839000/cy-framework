package com.cy.log.config;

import com.cy.mq.core.MqTopicSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志主题目录。
 * <p>
 * 根据日志组件启用状态、ACCESS 持久化状态及死信配置，汇总应用启动时需要声明的主题集合。
 * </p>
 *
 * @author pcy
 */
public class LogTopicCatalog {

    /**
     * 日志配置。
     */
    private final LogProperties properties;

    /**
     * 日志主题命名解析器。
     */
    private final LogTopicNaming topicNaming;

    /**
     * 构造日志主题目录。
     *
     * @param properties  日志配置
     * @param topicNaming 主题命名解析器
     */
    public LogTopicCatalog(LogProperties properties, LogTopicNaming topicNaming) {
        this.properties = properties;
        this.topicNaming = topicNaming;
    }

    /**
     * 汇总需要创建的主题列表。
     *
     * @return 主题定义列表
     */
    public List<MqTopicSpec> topicsToCreate() {
        Map<String, MqTopicSpec> topics = new LinkedHashMap<>();

        if (properties.getPublish().isEnabled() || properties.getPersistence().isEnabled()) {
            add(topics, topicNaming.businessTopic());
        }
        if ((properties.getPublish().isEnabled() && properties.getAccess().isEnabled())
                || (properties.getPersistence().isEnabled() && properties.getAccess().isPersist())) {
            add(topics, topicNaming.accessTopic());
        }
        if (properties.getPersistence().isEnabled() && properties.getPersistence().isDeadLetterEnabled()) {
            add(topics, topicNaming.businessDeadLetterTopic());
            add(topics, topicNaming.businessParkingLotTopic());
            if (properties.getAccess().isPersist()) {
                add(topics, topicNaming.accessDeadLetterTopic());
                add(topics, topicNaming.accessParkingLotTopic());
            }
        }

        return new ArrayList<>(topics.values());
    }

    /**
     * 向主题集合追加定义。
     */
    private void add(Map<String, MqTopicSpec> topics, String topicName) {
        if (topicName == null || topicName.isBlank()) {
            return;
        }
        topics.putIfAbsent(topicName, MqTopicSpec.of(topicName));
    }
}
