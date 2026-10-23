// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint.impl;

import com.cy.orrery.agent.runtime.checkpoint.Checkpoint;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内检查点存储默认实现。
 * <p>
 * 单实例可用（开发 / 单副本部署）；多副本部署请替换为
 * {@link RedisCheckpointStore}——挂起的运行在另一实例上恢复时，
 * 进程内实现拿不到检查点。
 * </p>
 * <p>
 * fencing：{@link #saveIfNewer} 经 {@link ConcurrentHashMap#compute} 原子比较
 * revision（旧 revision 迟到不覆盖新 revision）；{@link #nextRevision} 为
 * per-runId {@link AtomicLong} 递增（1, 2, 3…，重入 run 续接编号）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, Checkpoint> store = new ConcurrentHashMap<>();

    /** per-runId revision 计数器（nextRevision 分配；delete 时随检查点一并清理）。 */
    private final Map<String, AtomicLong> revisionCounters = new ConcurrentHashMap<>();

    @Override
    public Optional<Checkpoint> load(String runId) {
        return Optional.ofNullable(store.get(runId));
    }

    @Override
    public void save(Checkpoint checkpoint) {
        store.put(checkpoint.runId(), checkpoint);
    }

    @Override
    public boolean saveIfNewer(Checkpoint checkpoint) {
        // compute 原子性保证「读旧值 + 比较 + 写新值」无竞态窗口
        boolean[] applied = {false};
        store.compute(checkpoint.runId(), (runId, existing) -> {
            if (existing == null || checkpoint.revision() > existing.revision()) {
                applied[0] = true;
                return checkpoint;
            }
            return existing;
        });
        return applied[0];
    }

    @Override
    public long nextRevision(String runId) {
        return revisionCounters.computeIfAbsent(runId, key -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void delete(String runId) {
        store.remove(runId);
        revisionCounters.remove(runId);
    }

    /**
     * 当前存储的检查点数量（可观测性 / 泄漏防护验证用）。
     *
     * @return 数量
     */
    public int size() {
        return store.size();
    }
}
