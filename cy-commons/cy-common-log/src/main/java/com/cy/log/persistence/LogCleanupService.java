package com.cy.log.persistence;

import com.cy.log.config.LogProperties;
import com.cy.log.persistence.mapper.LogMapper;
import com.cy.log.util.LogUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 日志清理服务
 * <p>
 * 负责自动清理过期的日志数据，支持：
 * <ul>
 *     <li>按保留天数清理（业务日志和 ACCESS 日志独立保留策略）</li>
 *     <li>定时执行清理任务</li>
 *     <li>删除整个分表以提高效率</li>
 *     <li>分布式锁保证集群安全</li>
 * </ul>
 * <p>
 * 默认不启用，需要配置 cy.log.cleanup.enabled=true 开启
 *
 * @author pcy
 * @date 2026/01/29
 */
@Slf4j
public class LogCleanupService {

    private static final DateTimeFormatter PARTITION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private final LogMapper logMapper;
    private final LogProperties properties;
    private final LogTableRouter businessTableRouter;
    private final LogTableRouter accessTableRouter;
    private final StringRedisTemplate redisTemplate;

    public LogCleanupService(LogMapper logMapper, LogProperties properties,
                             LogTableRouter businessTableRouter,
                             LogTableRouter accessTableRouter,
                             StringRedisTemplate redisTemplate) {
        this.logMapper = logMapper;
        this.properties = properties;
        this.businessTableRouter = businessTableRouter;
        this.accessTableRouter = accessTableRouter;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 定时清理任务
     * <p>
     * 根据配置的Cron表达式执行，默认每天凌晨2点
     */
    @Scheduled(cron = "${cy.log.cleanup.cron:0 0 2 * * ?}")
    public void scheduledCleanup() {
        if (!properties.getCleanup().isEnabled()) {
            return;
        }

        // 分布式锁
        LogProperties.Cleanup cleanupConfig = properties.getCleanup();
        if (cleanupConfig.isClusterSafe() && redisTemplate != null) {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(cleanupConfig.getLockKey(), "1",
                            Duration.ofSeconds(cleanupConfig.getLockTtlSeconds()));
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("日志清理任务未获取到分布式锁，跳过执行");
                return;
            }
        }

        log.info("开始执行日志清理任务...");
        try {
            // 清理业务日志
            int businessDeleted = cleanupByPrefix(
                    businessTableRouter.getTablePrefix(),
                    properties.getCleanup().getRetentionDays(),
                    businessTableRouter);

            // 清理 ACCESS 日志
            int accessDeleted = cleanupByPrefix(
                    accessTableRouter.getTablePrefix(),
                    properties.getAccess().getRetentionDays(),
                    accessTableRouter);

            log.info("日志清理任务完成，业务日志删除 {} 个分表，ACCESS日志删除 {} 个分表",
                    businessDeleted, accessDeleted);
        } catch (Exception e) {
            log.error("日志清理任务执行失败", e);
        }
    }

    /**
     * 按前缀清理过期分表
     *
     * @param tablePrefix  表名前缀
     * @param retentionDays 保留天数
     * @param router        对应的分表路由器
     * @return 删除的表数量
     */
    private int cleanupByPrefix(String tablePrefix, int retentionDays, LogTableRouter router) {
        LocalDate expireDate = LocalDate.now().minusDays(retentionDays);
        String expirePartition = expireDate.format(PARTITION_FORMATTER);

        log.info("清理 {} 前缀 {} 之前的分表，保留天数: {}",
                tablePrefix, expirePartition, retentionDays);

        List<String> allTables = logMapper.findTablesByPrefix(tablePrefix);
        int deletedCount = 0;

        for (String tableName : allTables) {
            String tablePartition = extractPartition(tableName, tablePrefix);
            if (tablePartition == null) {
                continue;
            }

            if (tablePartition.compareTo(expirePartition) < 0) {
                try {
                    LogUtils.validateTableName(tableName);
                    logMapper.dropTable(tableName);
                    deletedCount++;
                    log.info("删除过期日志分表: {}", tableName);
                } catch (Exception e) {
                    log.error("删除日志分表失败: {}, error: {}", tableName, e.getMessage());
                }
            }
        }

        router.clearCache();
        return deletedCount;
    }

    /**
     * 执行清理（兼容旧 API）
     *
     * @return 删除的表数量
     */
    public int cleanup() {
        int businessDeleted = cleanupByPrefix(
                businessTableRouter.getTablePrefix(),
                properties.getCleanup().getRetentionDays(),
                businessTableRouter);
        int accessDeleted = cleanupByPrefix(
                accessTableRouter.getTablePrefix(),
                properties.getAccess().getRetentionDays(),
                accessTableRouter);
        return businessDeleted + accessDeleted;
    }

    /**
     * 从表名提取月份标识
     *
     * @param tableName   表名
     * @param tablePrefix 前缀
     * @return 月份标识，如 202601
     */
    private String extractPartition(String tableName, String tablePrefix) {
        try {
            String remaining = tableName.substring(tablePrefix.length() + 1);
            if (remaining.length() >= 6) {
                return remaining.substring(0, 6);
            }
        } catch (Exception e) {
            log.debug("提取分表月份失败: {}", tableName);
        }
        return null;
    }

    /**
     * 手动触发清理
     *
     * @param retentionDays 保留天数
     * @return 删除的表数量
     */
    public int manualCleanup(int retentionDays) {
        int businessDeleted = cleanupByPrefix(
                businessTableRouter.getTablePrefix(), retentionDays, businessTableRouter);
        int accessDeleted = cleanupByPrefix(
                accessTableRouter.getTablePrefix(), retentionDays, accessTableRouter);
        return businessDeleted + accessDeleted;
    }
}
