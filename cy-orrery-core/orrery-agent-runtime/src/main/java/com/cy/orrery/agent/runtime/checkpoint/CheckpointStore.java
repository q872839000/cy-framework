// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint;

import com.cy.orrery.agent.runtime.checkpoint.impl.InMemoryCheckpointStore;
import com.cy.orrery.agent.runtime.checkpoint.impl.RedisCheckpointStore;
import java.util.Optional;

/**
 * 检查点存储 SPI（对标 LangGraph 的 Checkpointer / BaseCheckpointSaver）。
 * <p>
 * 语义为「按 runId 保存最新一份快照」：save 覆盖、load 取最新、delete 清理。
 * 默认提供进程内实现（{@link InMemoryCheckpointStore}）；生产环境使用
 * {@link RedisCheckpointStore}（多副本共享、TTL 自动清理）。
 * 历史版本保留（时间旅行）不在本 SPI 语义内——如需版本链，宿主可在
 * save 时按 revision 自行归档。
 * </p>
 * <p>
 * <b>fencing</b>：{@link #saveIfNewer} 按 revision 比较后写入（旧 revision 迟到
 * 不覆盖新 revision——ASYNC 档乱序提交 / 多写者竞态防护）；{@link #nextRevision}
 * 为快照分配单调递增 revision（默认实现仅保证单 JVM 单调，分布式单调需覆写）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public interface CheckpointStore {

    /**
     * 加载运行的最新检查点。
     *
     * @param runId 运行 ID
     * @return 检查点；不存在为 empty
     */
    Optional<Checkpoint> load(String runId);

    /**
     * 保存检查点（同 runId 无条件覆盖旧值）。
     *
     * @param checkpoint 检查点
     */
    void save(Checkpoint checkpoint);

    /**
     * 按 revision fencing 保存：仅当检查点 revision 比存储中的新时写入。
     * <p>
     * ASYNC 档的快照提交与后台写入存在乱序窗口（旧 revision 后到），默认实现
     * 退化为无条件覆盖（单写者顺序场景下等价）；生产实现应覆写为原子比较
     * （进程内 CAS / Redis Lua），保证旧快照迟到不回滚状态。
     * </p>
     *
     * @param checkpoint 检查点
     * @return true 表示已写入；false 表示存在更新 revision，本快照被拒绝
     */
    default boolean saveIfNewer(Checkpoint checkpoint) {
        save(checkpoint);
        return true;
    }

    /**
     * 分配下一个 revision（快照修订号，单调递增）。
     * <p>
     * 默认实现 {@code System.nanoTime()}（单 JVM 单调兜底，重启不保证跨进程单调）；
     * 进程内实现覆写为 per-run 计数器、Redis 实现覆写为 INCR。
     * </p>
     *
     * @param runId 运行 ID
     * @return 下一个 revision（≥1）
     */
    default long nextRevision(String runId) {
        return Math.max(1L, System.nanoTime());
    }

    /**
     * 删除运行的检查点（终态清理）。
     *
     * @param runId 运行 ID
     */
    void delete(String runId);
}
