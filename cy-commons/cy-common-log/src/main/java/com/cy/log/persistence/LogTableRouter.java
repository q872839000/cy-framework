package com.cy.log.persistence;

import com.cy.log.persistence.mapper.LogMapper;
import com.cy.log.util.LogUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志分表路由器
 * <p>
 * 负责根据分表策略计算目标表名，支持：
 * <ul>
 *     <li>按月分表：每月一个分表</li>
 *     <li>按条数分表：单表达到最大行数后自动创建新表</li>
 * </ul>
 * <p>
 * 表名格式：{前缀}_{年月}_{序号}，如：sys_log_202601_001
 * <p>
 * 该类不再绑定 {@code LogProperties}，而是接收外部传入的 tablePrefix 和 maxRowsPerTable，
 * 使其可同时为业务日志和 ACCESS 日志服务。
 *
 * @author pcy
 * @date 2026/01/29
 */
@Slf4j
public class LogTableRouter {

    /**
     * 分表标识格式
     */
    private static final DateTimeFormatter PARTITION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 序号格式（3位数字）
     */
    private static final String INDEX_FORMAT = "%03d";

    /**
     * 日志Mapper
     */
    private final LogMapper logMapper;

    /**
     * 表名前缀
     */
    private final String tablePrefix;

    /**
     * 单表最大行数
     */
    private final int maxRowsPerTable;

    /**
     * 当前写入表名缓存
     * <p>
     * Key: 月份标识（yyyyMM），Value: 当前写入表名
     */
    private final Map<String, String> currentTableCache = new ConcurrentHashMap<>();

    /**
     * 表行数缓存
     * <p>
     * Key: 表名，Value: 行数
     */
    private final Map<String, Long> tableRowCountCache = new ConcurrentHashMap<>();

    public LogTableRouter(LogMapper logMapper, String tablePrefix, int maxRowsPerTable) {
        this.logMapper = logMapper;
        this.tablePrefix = tablePrefix;
        this.maxRowsPerTable = maxRowsPerTable;
    }

    /**
     * 获取表前缀
     */
    public String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * 获取当前写入表名
     * <p>
     * 根据当前月份和表的行数计算目标表名
     *
     * @return 写入表名
     */
    public String getWriteTable() {
        String partition = getCurrentPartition();
        return getWriteTable(partition);
    }

    /**
     * 获取指定月份的写入表名
     *
     * @param partition 月份标识（yyyyMM）
     * @return 写入表名
     */
    public String getWriteTable(String partition) {
        // 先从缓存获取
        String cachedTable = currentTableCache.get(partition);
        if (cachedTable != null) {
            // 检查是否需要切换到新表
            if (!shouldCreateNewTable(cachedTable)) {
                return cachedTable;
            }
        }

        // 计算当前应该使用的表
        return computeWriteTable(partition);
    }

    /**
     * 计算写入表名
     *
     * @param partition 月份标识
     * @return 表名
     */
    private synchronized String computeWriteTable(String partition) {
        // 查找该月份已存在的所有分表
        String pattern = tablePrefix + "_" + partition;
        List<String> existingTables = logMapper.findTablesByPrefix(pattern);

        if (existingTables.isEmpty()) {
            // 没有任何分表，创建第一个
            String tableName = buildTableName(partition, 1);
            ensureTableExists(tableName);
            currentTableCache.put(partition, tableName);
            return tableName;
        }

        // 检查最后一个分表的行数
        String lastTable = existingTables.get(existingTables.size() - 1);
        long rowCount = getTableRowCount(lastTable);

        if (rowCount >= maxRowsPerTable) {
            // 最后一个表已满，创建新表
            int newIndex = extractTableIndex(lastTable) + 1;
            String newTableName = buildTableName(partition, newIndex);
            ensureTableExists(newTableName);
            currentTableCache.put(partition, newTableName);
            // 清除旧表的行数缓存
            tableRowCountCache.remove(lastTable);
            return newTableName;
        }

        // 使用最后一个表
        currentTableCache.put(partition, lastTable);
        return lastTable;
    }

    /**
     * 判断是否需要创建新表
     *
     * @param tableName 当前表名
     * @return 如果需要创建新表返回true
     */
    private boolean shouldCreateNewTable(String tableName) {
        long rowCount = getTableRowCount(tableName);
        return rowCount >= maxRowsPerTable;
    }

    /**
     * 获取表的行数
     *
     * @param tableName 表名
     * @return 行数
     */
    private long getTableRowCount(String tableName) {
        // 使用缓存，每1000次写入后刷新
        Long cachedCount = tableRowCountCache.get(tableName);
        if (cachedCount != null && cachedCount % 1000 != 0) {
            return cachedCount;
        }

        try {
            long count = logMapper.countByTable(tableName);
            tableRowCountCache.put(tableName, count);
            return count;
        } catch (Exception e) {
            log.warn("查询表行数失败: {}, error: {}", tableName, e.getMessage());
            return 0;
        }
    }

    /**
     * 增加表行数计数
     *
     * @param tableName 表名
     */
    public void incrementRowCount(String tableName) {
        tableRowCountCache.compute(tableName, (k, v) -> v == null ? 1 : v + 1);
    }

    /**
     * 增加表行数计数。
     *
     * @param tableName 表名
     * @param delta     增量
     */
    public void incrementRowCount(String tableName, int delta) {
        if (delta <= 0) {
            return;
        }
        tableRowCountCache.compute(tableName, (k, v) -> v == null ? delta : v + delta);
    }

    /**
     * 构建表名
     *
     * @param partition 月份标识
     * @param index     序号
     * @return 表名
     */
    private String buildTableName(String partition, int index) {
        String name = tablePrefix + "_" + partition + "_" + String.format(INDEX_FORMAT, index);
        LogUtils.validateTableName(name);
        return name;
    }

    /**
     * 从表名中提取序号
     *
     * @param tableName 表名
     * @return 序号
     */
    private int extractTableIndex(String tableName) {
        try {
            String[] parts = tableName.split("_");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 确保表存在
     *
     * @param tableName 表名
     */
    private void ensureTableExists(String tableName) {
        try {
            LogUtils.validateTableName(tableName);
            String exists = logMapper.checkTableExists(tableName);
            if (!StringUtils.hasText(exists)) {
                logMapper.createTable(tableName);
                log.info("创建日志分表: {}", tableName);
            }
        } catch (Exception e) {
            log.error("创建日志分表失败: {}, error: {}", tableName, e.getMessage());
        }
    }

    /**
     * 获取查询表名列表
     * <p>
     * 根据时间范围计算需要查询的所有分表
     *
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime   结束时间（毫秒时间戳）
     * @return 表名列表
     */
    public List<String> getQueryTables(Long startTime, Long endTime) {
        List<String> tables = new ArrayList<>();

        // 计算时间范围涉及的月份
        YearMonth startMonth = toYearMonth(startTime);
        YearMonth endMonth = toYearMonth(endTime);

        // 遍历每个月份
        YearMonth current = startMonth;
        while (!current.isAfter(endMonth)) {
            String partition = current.format(PARTITION_FORMATTER);
            String pattern = tablePrefix + "_" + partition;

            // 查找该月份的所有分表
            List<String> monthTables = logMapper.findTablesByPrefix(pattern);
            tables.addAll(monthTables);

            current = current.plusMonths(1);
        }

        return tables;
    }

    /**
     * 获取当前月份标识
     *
     * @return 月份标识（yyyyMM）
     */
    private String getCurrentPartition() {
        return LocalDateTime.now().format(PARTITION_FORMATTER);
    }

    /**
     * 时间戳转YearMonth
     *
     * @param timestamp 毫秒时间戳
     * @return YearMonth
     */
    private YearMonth toYearMonth(Long timestamp) {
        if (timestamp == null) {
            return YearMonth.now();
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()
        );
        return YearMonth.from(dateTime);
    }

    /**
     * 清除缓存
     * <p>
     * 用于测试或手动刷新
     */
    public void clearCache() {
        currentTableCache.clear();
        tableRowCountCache.clear();
    }
}
