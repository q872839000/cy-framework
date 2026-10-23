package com.cy.mq.core;

/**
 * 消息队列驱动类型
 * <p>
 * 用于选择消息的投递与消费方式：
 * </p>
 * <ul>
 *     <li>KAFKA：Kafka</li>
 *     <li>REDIS：Redis（不指定 groupId 时为广播模式，指定 groupId 时为单消费模式）</li>
 *     <li>LOCAL：本地队列（进程内发布订阅）</li>
 * </ul>
 *
 * @author pcy
 */
public enum MqDriverType {

    /** Kafka */
    KAFKA,

    /** Redis（广播/单消费模式） */
    REDIS,

    /** 本地队列 */
    LOCAL
}
