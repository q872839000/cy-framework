package com.cy.mq.core;

/**
 * 订阅句柄
 * <p>
 * 用于在运行时取消订阅并释放资源。
 * </p>
 *
 * @author pcy
 */
public interface MqSubscription {

    /**
     * 取消订阅
     */
    void close();
}
