// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.autoconfigure;

import com.cy.orrery.memory.semantic.SemanticMemoryService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 记忆治理清扫调度（墓碑物理清理；自带调度线程，不依赖宿主 @EnableScheduling）。
 *
 * @author pcy
 */
public class MemoryPurgeScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryPurgeScheduler.class);

    /** 最小清扫间隔（秒）：低于该值无意义（墓碑清理是低频治理动作）且易空转。 */
    private static final long MIN_INTERVAL_SECONDS = 60L;

    /** 配置。 */
    private final MemoryProperties properties;

    /** 语义记忆服务（未装配时无清扫面）。 */
    private final SemanticMemoryService semanticService;

    /** 调度线程。 */
    private final ScheduledExecutorService executor;

    /**
     * 构造清扫调度器（语义记忆未装配时无清扫面，start 后周期空转安全）。
     *
     * @param properties      配置
     * @param semanticService 语义记忆服务（延迟解析）
     */
    public MemoryPurgeScheduler(MemoryProperties properties,
                                ObjectProvider<SemanticMemoryService> semanticService) {
        this.properties = properties;
        this.semanticService = semanticService.getIfAvailable();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("memory-purge-", 0).factory());
    }

    /** 启动周期清扫（首次延迟一个间隔，避让启动期）。 */
    public void start() {
        long interval = Math.max(MIN_INTERVAL_SECONDS, properties.getPurge().getIntervalSeconds());
        executor.scheduleWithFixedDelay(this::purgeSafely, interval, interval, TimeUnit.SECONDS);
    }

    /** 清扫一次（墓碑物理清理）。 */
    void purgeSafely() {
        if (semanticService == null) {
            return;
        }
        try {
            Instant threshold = Instant.now().minus(
                    properties.getPurge().getTombstoneRetentionSeconds(), ChronoUnit.SECONDS);
            int purged = semanticService.purgeTombstoned(threshold);
            if (purged > 0) {
                log.info("记忆墓碑清理完成: purged={}", purged);
            }
        } catch (RuntimeException e) {
            log.warn("记忆墓碑清理失败（下周期重试）: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
