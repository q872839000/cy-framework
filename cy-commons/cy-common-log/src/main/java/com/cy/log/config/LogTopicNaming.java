package com.cy.log.config;

import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqDriverType;

/**
 * 日志主题命名解析器。
 * <p>
 * 在未显式配置日志主题时，根据当前 MQ 驱动返回对应的默认主题命名，
 * 以兼容 Kafka 与 Redis/Local 不同的主题命名约束。
 * </p>
 *
 * @author pcy
 */
public class LogTopicNaming {

    /**
     * MQ 配置。
     */
    private final MqProperties mqProperties;

    /**
     * 日志配置。
     */
    private final LogProperties logProperties;

    /**
     * 构造日志主题命名解析器。
     *
     * @param mqProperties  MQ 配置
     * @param logProperties 日志配置
     */
    public LogTopicNaming(MqProperties mqProperties, LogProperties logProperties) {
        this.mqProperties = mqProperties;
        this.logProperties = logProperties;
    }

    /**
     * 获取业务日志 Topic。
     *
     * @return 业务日志 Topic
     */
    public String businessTopic() {
        return defaultIfBlank(logProperties.getMq().getTopic(), defaultBusinessTopic());
    }

    /**
     * 获取业务日志死信 Topic。
     *
     * @return 业务日志死信 Topic
     */
    public String businessDeadLetterTopic() {
        return defaultIfBlank(logProperties.getMq().getDeadLetterTopic(), defaultBusinessDeadLetterTopic());
    }

    /**
     * 获取业务日志死信最终停放 Topic。
     *
     * @return 业务日志死信最终停放 Topic
     */
    public String businessParkingLotTopic() {
        return defaultIfBlank(logProperties.getMq().getDeadLetterParkingLotTopic(), defaultBusinessParkingLotTopic());
    }

    /**
     * 获取 ACCESS 日志 Topic。
     *
     * @return ACCESS 日志 Topic
     */
    public String accessTopic() {
        return defaultIfBlank(logProperties.getAccess().getTopic(), defaultAccessTopic());
    }

    /**
     * 获取 ACCESS 日志死信 Topic。
     *
     * @return ACCESS 日志死信 Topic
     */
    public String accessDeadLetterTopic() {
        return defaultIfBlank(logProperties.getAccess().getDeadLetterTopic(), defaultAccessDeadLetterTopic());
    }

    /**
     * 获取 ACCESS 日志死信最终停放 Topic。
     *
     * @return ACCESS 日志死信最终停放 Topic
     */
    public String accessParkingLotTopic() {
        return defaultIfBlank(logProperties.getAccess().getDeadLetterParkingLotTopic(), defaultAccessParkingLotTopic());
    }

    /**
     * 获取业务日志默认 Topic。
     */
    private String defaultBusinessTopic() {
        return isKafkaDriver() ? "cy.log.record" : "cy:log:record";
    }

    /**
     * 获取业务日志死信默认 Topic。
     */
    private String defaultBusinessDeadLetterTopic() {
        return isKafkaDriver() ? "cy.log.record.dlq" : "cy:log:record:dlq";
    }

    /**
     * 获取业务日志死信最终停放默认 Topic。
     */
    private String defaultBusinessParkingLotTopic() {
        return isKafkaDriver() ? "cy.log.record.dlq.parking" : "cy:log:record:dlq:parking";
    }

    /**
     * 获取 ACCESS 日志默认 Topic。
     */
    private String defaultAccessTopic() {
        return isKafkaDriver() ? "cy.log.access" : "cy:log:access";
    }

    /**
     * 获取 ACCESS 日志死信默认 Topic。
     */
    private String defaultAccessDeadLetterTopic() {
        return isKafkaDriver() ? "cy.log.access.dlq" : "cy:log:access:dlq";
    }

    /**
     * 获取 ACCESS 日志死信最终停放默认 Topic。
     */
    private String defaultAccessParkingLotTopic() {
        return isKafkaDriver() ? "cy.log.access.dlq.parking" : "cy:log:access:dlq:parking";
    }

    /**
     * 判断当前是否为 Kafka 驱动。
     */
    private boolean isKafkaDriver() {
        return mqProperties.getDriver() == MqDriverType.KAFKA;
    }

    /**
     * 当值为空白时回退默认值。
     */
    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
