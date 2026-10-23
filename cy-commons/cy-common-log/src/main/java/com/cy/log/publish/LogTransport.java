package com.cy.log.publish;

/**
 * 日志传输抽象。
 * <p>
 * 日志组件通过该接口把治理后的日志发送到具体出口，从而保持治理层与传输层解耦。
 * </p>
 *
 * @author pcy
 * @date 2026/04/14
 */
public interface LogTransport {

    /**
     * 发送日志。
     *
     * @param task 发布任务
     * @throws Exception 发送失败
     */
    void publish(LogPublishTask task) throws Exception;
}
