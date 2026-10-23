// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 长期记忆本体存储 SPI（MySQL 实现随组件提供；仅按 ID 与结构化过滤存取，
 * 不承担语义召回——语义召回走向量索引）。
 *
 * @author pcy
 */
public interface MemoryStore {

    /**
     * 写入新条目（含 CREATE 审计由调用方在同一事务内追加）。
     *
     * @param item 条目
     */
    void save(MemoryItem item);

    /**
     * 按 ID 读取（含非 ACTIVE 状态；不存在返回 empty）。
     *
     * @param memoryId 记忆 ID
     * @return 条目
     */
    Optional<MemoryItem> get(String memoryId);

    /**
     * 乐观锁更新（版本不匹配返回 false）。
     *
     * @param item            新值
     * @param expectedVersion 期望版本
     * @return 是否应用成功
     */
    boolean update(MemoryItem item, long expectedVersion);

    /**
     * 命中回馈（召回命中计数 + 最近召回时刻）。
     * <p>
     * 专用轻量路径：原子自增、不动 version（version 语义为内容修订版本，
     * 访问统计不是内容变更），也不做乐观锁校验——计数允许在并发下
     * 最后一次写入获胜，不会因并发召回把更正/状态迁移顶出版本冲突。
     * </p>
     *
     * @param memoryId   记忆 ID
     * @param accessedAt 召回时刻
     */
    void recordAccess(String memoryId, Instant accessedAt);

    /**
     * 物理删除（隐私擦除用；审计链由调用方处理）。
     *
     * @param memoryId 记忆 ID
     */
    void delete(String memoryId);

    /**
     * 结构化过滤检索（按 createdAt 升序，超 limit 截断）。
     *
     * @param query 查询条件
     * @return 命中条目
     */
    List<MemoryItem> recall(MemoryQuery query);

    /**
     * 统计 scope 下 ACTIVE 条目数（配额判定）。
     *
     * @param scope 归属范围
     * @return 条数
     */
    long countActive(MemoryScope scope);

    /**
     * 追加审计修订。
     *
     * @param revision 修订
     */
    void appendRevision(MemoryRevision revision);

    /**
     * 审计链查询（按时间升序）。
     *
     * @param memoryId 记忆 ID
     * @return 审计链
     */
    List<MemoryRevision> history(String memoryId);

    /**
     * 物理清理某条记忆的全部审计修订（彻底遗忘用）。
     *
     * @param memoryId 记忆 ID
     */
    void purgeRevisions(String memoryId);

    /**
     * 物理清理墓碑（非 ACTIVE 且 updatedAt 早于阈值）。
     *
     * @param olderThan 阈值时刻
     * @return 删除条数
     */
    int purgeTombstoned(Instant olderThan);

    /**
     * 事务包装（JDBC 实现为数据库事务；无事务后端默认直跑）。
     *
     * @param work 工作单元
     * @param <T>  返回值类型
     * @return 工作单元返回值
     */
    default <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }
}
