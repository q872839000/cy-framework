package com.cy.log.persistence.dlq;

import com.cy.log.core.LogRecord;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 日志持久化死信批次。
 *
 * @author pcy
 */
@Data
public class LogDeadLetterBatch {

    /**
     * 批次类型。
     */
    private String batchType;

    /**
     * 原始日志记录。
     */
    private List<LogRecord> records;

    /**
     * 目标表名。
     */
    private Map<String, Integer> targetTables;

    /**
     * 失败原因。
     */
    private String errorMessage;

    /**
     * 失败时间。
     */
    private Long failedAt;

    /**
     * 已回放次数。
     */
    private Integer replayAttempt;

    /**
     * 最近一次回放时间。
     */
    private Long lastReplayedAt;
}
