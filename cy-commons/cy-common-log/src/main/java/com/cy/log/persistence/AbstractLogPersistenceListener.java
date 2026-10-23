package com.cy.log.persistence;

import com.cy.log.config.LogProperties;
import com.cy.log.core.LogRecord;
import com.cy.log.persistence.dlq.LogDeadLetterBatch;
import com.cy.log.persistence.entity.LogEntity;
import com.cy.log.persistence.mapper.LogMapper;
import com.cy.log.util.LogUtils;
import com.cy.mq.core.MqTemplate;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志持久化监听器公共逻辑。
 *
 * @author pcy
 */
@Slf4j
abstract class AbstractLogPersistenceListener {

    private static final String DLQ_REPLAY_DISABLED_MESSAGE = "日志死信自动回放已关闭";
    private static final String PARKING_LOT_NAME = "parking lot";

    protected final LogMapper logMapper;
    protected final LogTableRouter tableRouter;
    protected final MqTemplate mqTemplate;
    protected final LogProperties properties;
    private final TransactionTemplate transactionTemplate;

    protected AbstractLogPersistenceListener(LogMapper logMapper,
                                             LogTableRouter tableRouter,
                                             MqTemplate mqTemplate,
                                             LogProperties properties,
                                             PlatformTransactionManager transactionManager) {
        this.logMapper = logMapper;
        this.tableRouter = tableRouter;
        this.mqTemplate = mqTemplate;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 批量持久化日志。
     *
     * @param logRecords 日志记录
     */
    protected void persistBatch(List<LogRecord> logRecords) {
        persistBatchWithRetry(logRecords, true, 1);
    }

    /**
     * 带重试的批量持久化（同步重试，确保持久化完成后才返回）。
     */
    private void persistBatchWithRetry(List<LogRecord> logRecords, boolean allowDeadLetter, int attempt) {
        if (logRecords == null || logRecords.isEmpty()) {
            return;
        }

        List<BatchEntry> entries = resolveBatchEntries(logRecords);
        if (entries.isEmpty()) {
            return;
        }

        int maxAttempts = Math.max(1, properties.getPersistence().getMaxRetryAttempts());

        for (int currentAttempt = attempt; currentAttempt <= maxAttempts; currentAttempt++) {
            try {
                Map<String, Integer> tableCounts = writeInTransaction(entries);
                tableCounts.forEach((tableName, count) -> tableRouter.incrementRowCount(tableName, count));
                markPersistSuccess(entries.size());
                log.debug("{} 日志批量持久化完成: size={}, tables={}",
                        logTypeName(), entries.size(), tableCounts.keySet());
                return;  // 成功后立即返回
            } catch (Exception e) {
                if (currentAttempt >= maxAttempts) {
                    markPersistFail(entries.size());
                    if (allowDeadLetter) {
                        handleFinalFailure(logRecords, entries, e);
                    } else {
                        throw new IllegalStateException(logTypeName() + " 日志死信回放失败", e);
                    }
                    return;
                }

                long backoffMs = properties.getPersistence().getRetryBackoffMs();
                log.warn("{} 日志批量持久化失败，将在 {}ms 后同步重试: attempt={}/{}, size={}, error={}",
                        logTypeName(), backoffMs, currentAttempt, maxAttempts, entries.size(), e.getMessage());

                // 同步重试，阻塞当前线程
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(logTypeName() + " 日志持久化重试被中断", ie);
                }
            }
        }
    }

    /**
     * 回放一批死信日志。
     */
    protected void replayDeadLetterBatch(LogDeadLetterBatch deadLetterBatch) {
        if (deadLetterBatch == null || deadLetterBatch.getRecords() == null || deadLetterBatch.getRecords().isEmpty()) {
            return;
        }
        if (!properties.getPersistence().isDeadLetterReplayEnabled()) {
            parkDeadLetterBatch(deadLetterBatch, DLQ_REPLAY_DISABLED_MESSAGE);
            return;
        }
        try {
            persistBatchWithRetry(deadLetterBatch.getRecords(), false, 1);
            log.info("{} 日志死信回放成功: size={}, attempt={}",
                    logTypeName(), deadLetterBatch.getRecords().size(), resolveReplayAttempt(deadLetterBatch));
        } catch (Exception exception) {
            handleReplayFailure(deadLetterBatch, exception);
        }
    }
    private List<BatchEntry> resolveBatchEntries(List<LogRecord> logRecords) {
        List<BatchEntry> entries = new ArrayList<>(logRecords.size());
        for (LogRecord logRecord : logRecords) {
            if (logRecord == null) {
                continue;
            }
            LogEntity entity = convertToEntity(logRecord);
            String tableName = tableRouter.getWriteTable(logRecord.getTablePartition());
            LogUtils.validateTableName(tableName);
            entries.add(new BatchEntry(tableName, entity));
        }
        return entries;
    }

    /**
     * 在事务中写入批次。
     */
    private Map<String, Integer> writeInTransaction(List<BatchEntry> entries) {
        Map<String, List<LogEntity>> grouped = groupByTable(entries);
        Map<String, Integer> tableCounts = new LinkedHashMap<>();

        transactionTemplate.executeWithoutResult(status -> {
            int batchSize = Math.max(1, properties.getPersistence().getBatchSize());
            for (Map.Entry<String, List<LogEntity>> group : grouped.entrySet()) {
                String tableName = group.getKey();
                List<LogEntity> entities = group.getValue();
                for (int start = 0; start < entities.size(); start += batchSize) {
                    int end = Math.min(start + batchSize, entities.size());
                    List<LogEntity> chunk = entities.subList(start, end);
                    logMapper.insertBatchToTable(tableName, chunk);
                    tableCounts.merge(tableName, chunk.size(), Integer::sum);
                }
            }
        });

        return tableCounts;
    }

    /**
     * 按表名分组。
     */
    private Map<String, List<LogEntity>> groupByTable(List<BatchEntry> entries) {
        Map<String, List<LogEntity>> grouped = new LinkedHashMap<>();
        for (BatchEntry entry : entries) {
            grouped.computeIfAbsent(entry.tableName(), key -> new ArrayList<>()).add(entry.entity());
        }
        return grouped;
    }

    /**
     * 最终失败处理。
     */
    private void handleFinalFailure(List<LogRecord> logRecords, List<BatchEntry> entries, Exception exception) {
        if (!properties.getPersistence().isDeadLetterEnabled()) {
            throw new IllegalStateException(logTypeName() + " 日志批量持久化失败，且死信已关闭", exception);
        }

        LogDeadLetterBatch deadLetterBatch = new LogDeadLetterBatch();
        deadLetterBatch.setBatchType(logTypeName());
        deadLetterBatch.setRecords(logRecords);
        deadLetterBatch.setTargetTables(resolveTableCounts(entries));
        deadLetterBatch.setErrorMessage(exception != null ? exception.getMessage() : null);
        deadLetterBatch.setFailedAt(System.currentTimeMillis());
        deadLetterBatch.setReplayAttempt(0);

        try {
            mqTemplate.publish(deadLetterTopic(), deadLetterBatch);
            log.error("{} 日志批量持久化失败，已写入死信: topic={}, size={}",
                    logTypeName(), deadLetterTopic(), entries.size(), exception);
        } catch (Exception deadLetterException) {
            throw new IllegalStateException(logTypeName() + " 日志死信写入失败", deadLetterException);
        }
    }

    /**
     * 处理死信回放失败。
     * <p>
     * 未达到上限时重新入队；达到上限时转入 parking lot。
     * </p>
     */
    private void handleReplayFailure(LogDeadLetterBatch deadLetterBatch, Exception exception) {
        int nextAttempt = resolveReplayAttempt(deadLetterBatch) + 1;
        int maxAttempts = Math.max(1, properties.getPersistence().getDeadLetterReplayMaxAttempts());
        if (nextAttempt >= maxAttempts) {
            LogDeadLetterBatch parkingLotBatch = copyDeadLetterBatch(deadLetterBatch, exception, nextAttempt);
            parkDeadLetterBatch(parkingLotBatch, exception != null ? exception.getMessage() : null);
            return;
        }
        sleepBeforeReplay();
        LogDeadLetterBatch retriedBatch = copyDeadLetterBatch(deadLetterBatch, exception, nextAttempt);
        try {
            mqTemplate.publish(deadLetterTopic(), retriedBatch);
            log.warn("{} 日志死信回放失败，已重新入队: topic={}, size={}, attempt={}/{}, error={}",
                    logTypeName(), deadLetterTopic(), retriedBatch.getRecords().size(), nextAttempt, maxAttempts,
                    exception != null ? exception.getMessage() : null);
        } catch (Exception publishException) {
            throw new IllegalStateException(logTypeName() + " 日志死信重入队失败", publishException);
        }
    }

    private void parkDeadLetterBatch(LogDeadLetterBatch deadLetterBatch, String errorMessage) {
        if (deadLetterBatch == null) {
            return;
        }
        if (errorMessage != null) {
            deadLetterBatch.setErrorMessage(errorMessage);
        }
        if (deadLetterBatch.getLastReplayedAt() == null) {
            deadLetterBatch.setLastReplayedAt(System.currentTimeMillis());
        }
        try {
            mqTemplate.publish(parkingLotTopic(), deadLetterBatch);
            log.error("{} 日志死信已转入 {}: topic={}, size={}, attempt={}, error={}",
                    logTypeName(), PARKING_LOT_NAME,
                    parkingLotTopic(),
                    deadLetterBatch.getRecords() != null ? deadLetterBatch.getRecords().size() : 0,
                    deadLetterBatch.getReplayAttempt(), deadLetterBatch.getErrorMessage());
        } catch (Exception exception) {
            throw new IllegalStateException(logTypeName() + " 日志 " + PARKING_LOT_NAME + " 写入失败", exception);
        }
    }

    /**
     * 复制死信批次并附加新的回放元信息。
     */
    private LogDeadLetterBatch copyDeadLetterBatch(LogDeadLetterBatch source, Exception exception, int replayAttempt) {
        LogDeadLetterBatch copied = new LogDeadLetterBatch();
        copied.setBatchType(source.getBatchType());
        copied.setRecords(source.getRecords());
        copied.setTargetTables(source.getTargetTables());
        copied.setFailedAt(source.getFailedAt());
        copied.setReplayAttempt(replayAttempt);
        copied.setLastReplayedAt(System.currentTimeMillis());
        copied.setErrorMessage(exception != null ? exception.getMessage() : source.getErrorMessage());
        return copied;
    }

    /**
     * 获取当前死信批次的已回放次数。
     */
    private int resolveReplayAttempt(LogDeadLetterBatch deadLetterBatch) {
        return deadLetterBatch.getReplayAttempt() != null ? deadLetterBatch.getReplayAttempt() : 0;
    }

    /**
     * 统计目标表写入条数。
     */
    private Map<String, Integer> resolveTableCounts(List<BatchEntry> entries) {
        Map<String, Integer> tableCounts = new LinkedHashMap<>();
        for (BatchEntry entry : entries) {
            tableCounts.merge(entry.tableName(), 1, Integer::sum);
        }
        return tableCounts;
    }


    /**
     * 死信回放重新入队前的退避。
     */
    private void sleepBeforeReplay() {
        long backoffMs = properties.getPersistence().getDeadLetterReplayBackoffMs();
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("日志死信回放重试被中断", e);
        }
    }

    /**
     * 转换为持久化实体。
     */
    LogEntity convertToEntity(LogRecord record) {
        LogEntity entity = new LogEntity();
        BeanUtils.copyProperties(record, entity);

        if (record.getLogType() != null) {
            entity.setLogType(record.getLogType().name());
        }

        entity.setInputArgs(safeText(entity.getInputArgs()));
        entity.setReturnValue(safeText(entity.getReturnValue()));
        entity.setRequestBody(safeText(entity.getRequestBody()));
        entity.setResponseBody(safeText(entity.getResponseBody()));
        entity.setStackTrace(safeText(entity.getStackTrace()));
        entity.setRequestParams(safeText(entity.getRequestParams()));
        entity.setRequestHeaders(safeText(entity.getRequestHeaders()));
        entity.setBeforeData(safeText(entity.getBeforeData()));
        entity.setAfterData(safeText(entity.getAfterData()));

        return entity;
    }

    /**
     * 安全截断 TEXT 字段，防止超出数据库 TEXT 类型限制。
     * <p>
     * 对于 JSON 格式的数据，尝试在合适的位置截断以保持 JSON 完整性。
     */
    private String safeText(String value) {
        int limit = properties.getTextSafeLimit();
        if (value == null || value.length() <= limit) {
            return value;
        }

        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return safeJsonTruncate(value, limit);
        }

        return value.substring(0, limit) + LogUtils.TRUNCATED_SUFFIX;
    }

    /**
     * 智能截断 JSON 字符串，尝试保持 JSON 格式完整性。
     */
    private String safeJsonTruncate(String json, int maxLength) {
        try {
            JSON.parse(json);

            int truncatePos = findSafeJsonTruncatePosition(json, maxLength);
            if (truncatePos > 0) {
                char firstChar = json.trim().charAt(0);
                String closingChar = firstChar == '{' ? "}" : "]";
                return json.substring(0, truncatePos) + "..." + closingChar;
            }
        } catch (JSONException e) {
            // 不是有效 JSON，直接截断
        }

        return json.substring(0, maxLength) + LogUtils.TRUNCATED_SUFFIX;
    }

    /**
     * 在 JSON 字符串中找到安全的截断位置。
     * <p>
     * 在 maxLength 附近找到最近的 "," 或 "}" 或 "]"，避免截断在字段中间。
     */
    private int findSafeJsonTruncatePosition(String json, int maxLength) {
        int searchStart = Math.min(maxLength, json.length());
        int searchEnd = Math.max(0, searchStart - 200);

        for (int i = searchStart - 1; i >= searchEnd; i--) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || c == ']') {
                return i;
            }
        }

        return -1;
    }

    /**
     * 日志类型名称。
     */
    protected abstract String logTypeName();

    /**
     * 死信 Topic。
     */
    protected abstract String deadLetterTopic();

    /**
     * 死信最终停放 Topic。
     */
    protected abstract String parkingLotTopic();

    /**
     * 记录持久化成功指标。
     */
    protected abstract void markPersistSuccess(int count);

    /**
     * 记录持久化失败指标。
     */
    protected abstract void markPersistFail(int count);

    private record BatchEntry(String tableName, LogEntity entity) {
    }
}
