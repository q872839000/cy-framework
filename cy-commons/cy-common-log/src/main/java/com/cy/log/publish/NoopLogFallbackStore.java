package com.cy.log.publish;

/**
 * 空实现兜底存储。
 *
 * @author pcy
 * @date 2026/04/14
 */
public class NoopLogFallbackStore implements LogFallbackStore {

    @Override
    public boolean store(LogPublishTask task, String reason, Exception exception) {
        return false;
    }
}
