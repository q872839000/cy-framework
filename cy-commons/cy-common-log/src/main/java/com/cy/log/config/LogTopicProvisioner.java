package com.cy.log.config;

import com.cy.mq.core.MqTemplate;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 日志主题声明器。
 * <p>
 * 在 Spring 单例 Bean 初始化完成后，根据日志组件声明的主题清单调用 MQ 抽象进行主题创建。
 * </p>
 *
 * @author pcy
 */
public class LogTopicProvisioner implements SmartInitializingSingleton {

    /**
     * MQ 模板。
     */
    private final MqTemplate mqTemplate;

    /**
     * 日志主题目录。
     */
    private final LogTopicCatalog topicCatalog;

    /**
     * 构造日志主题声明器。
     *
     * @param mqTemplate   MQ 模板
     * @param topicCatalog 日志主题目录
     */
    public LogTopicProvisioner(MqTemplate mqTemplate, LogTopicCatalog topicCatalog) {
        this.mqTemplate = mqTemplate;
        this.topicCatalog = topicCatalog;
    }

    /**
     * 在单例初始化完成后声明日志主题。
     */
    @Override
    public void afterSingletonsInstantiated() {
        mqTemplate.createTopics(topicCatalog.topicsToCreate());
    }
}
