package com.cy.log.publish;

/**
 * 日志发布车道。
 *
 * @author pcy
 * @date 2026/04/14
 */
public enum LogLane {

    /**
     * 业务日志车道。
     */
    BUSINESS("business"),

    /**
     * ACCESS 日志车道。
     */
    ACCESS("access");

    private final String metricKey;

    LogLane(String metricKey) {
        this.metricKey = metricKey;
    }

    public String metricKey() {
        return metricKey;
    }
}
