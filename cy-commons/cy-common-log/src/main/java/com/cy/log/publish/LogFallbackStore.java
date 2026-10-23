package com.cy.log.publish;

/**
 * 日志本地兜底存储。
 *
 * @author pcy
 * @date 2026/04/14
 */
public interface LogFallbackStore {

    /**
     * 写入兜底存储。
     *
     * @param task      发布任务
     * @param reason    兜底原因
     * @param exception 原始异常
     * @return 写入是否成功
     */
    boolean store(LogPublishTask task, String reason, Exception exception);
}
