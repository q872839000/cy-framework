// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryRevision;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.MemoryStatus;
import com.cy.orrery.memory.api.MemoryStore;
import com.cy.orrery.memory.api.Provenance;
import com.cy.orrery.memory.api.ScopeType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 长期记忆 MySQL 存储（表：orrery_memory_item / orrery_memory_revision，见 schema.sql）。
 * <p>
 * 只按 ID 与结构化过滤存取，不承担语义召回（召回走向量索引）。
 * 时间列统一 BIGINT epoch millis（与全仓 BaseEntity 约定一致）。
 * </p>
 *
 * @author pcy
 */
public class JdbcMemoryStore implements MemoryStore {

    /** JDBC。 */
    private final JdbcTemplate jdbc;

    /** 事务。 */
    private final TransactionTemplate tx;

    /**
     * 构造长期记忆存储。
     *
     * @param jdbc JDBC
     * @param tx   事务模板
     */
    public JdbcMemoryStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void save(MemoryItem item) {
        jdbc.update("INSERT INTO orrery_memory_item (memory_id, tenant_id, scope_type, owner_id, "
                        + "kind, abstract_text, content, source_ref, importance, status, version, "
                        + "access_count, created_at, updated_at, last_accessed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Long.parseLong(item.getMemoryId()), item.getScope().tenantId(),
                item.getScope().type().name(), item.getScope().ownerId(),
                item.getKind().name(), item.getAbstractText(), item.getContent(),
                item.getSourceRef(), item.getImportance(), item.getStatus().name(),
                item.getVersion(), item.getAccessCount(),
                millis(item.getCreatedAt()), millis(item.getUpdatedAt()),
                item.getLastAccessedAt() == null ? null : item.getLastAccessedAt().toEpochMilli());
    }

    @Override
    public Optional<MemoryItem> get(String memoryId) {
        List<MemoryItem> hits = jdbc.query("SELECT * FROM orrery_memory_item WHERE memory_id=?",
                this::map, Long.parseLong(memoryId));
        return hits.stream().findFirst();
    }

    @Override
    public boolean update(MemoryItem item, long expectedVersion) {
        int updated = jdbc.update("UPDATE orrery_memory_item SET kind=?, abstract_text=?, content=?, "
                        + "source_ref=?, importance=?, status=?, version=?, access_count=?, "
                        + "updated_at=?, last_accessed_at=? WHERE memory_id=? AND version=?",
                item.getKind().name(), item.getAbstractText(), item.getContent(),
                item.getSourceRef(), item.getImportance(), item.getStatus().name(),
                item.getVersion(), item.getAccessCount(),
                millis(item.getUpdatedAt()),
                item.getLastAccessedAt() == null ? null : item.getLastAccessedAt().toEpochMilli(),
                Long.parseLong(item.getMemoryId()), expectedVersion);
        return updated == 1;
    }

    @Override
    public void recordAccess(String memoryId, Instant accessedAt) {
        jdbc.update("UPDATE orrery_memory_item SET access_count=access_count+1, last_accessed_at=? "
                        + "WHERE memory_id=?",
                accessedAt.toEpochMilli(), Long.parseLong(memoryId));
    }

    @Override
    public void delete(String memoryId) {
        jdbc.update("DELETE FROM orrery_memory_item WHERE memory_id=?", Long.parseLong(memoryId));
    }

    @Override
    public List<MemoryItem> recall(MemoryQuery query) {
        StringBuilder sql = new StringBuilder("SELECT * FROM orrery_memory_item WHERE 1=1");
        List<Object> args = new ArrayList<>();
        sql.append(" AND status IN (")
                .append(query.effectiveStatuses().stream().map(s -> "?")
                        .collect(Collectors.joining(",")))
                .append(")");
        query.effectiveStatuses().forEach(s -> args.add(s.name()));
        if (query.getTenantId() != null) {
            sql.append(" AND tenant_id=?");
            args.add(query.getTenantId());
        }
        if (query.getScopeType() != null) {
            sql.append(" AND scope_type=?");
            args.add(query.getScopeType().name());
        }
        if (query.getOwnerId() != null) {
            sql.append(" AND owner_id=?");
            args.add(query.getOwnerId());
        }
        if (query.getKinds() != null && !query.getKinds().isEmpty()) {
            sql.append(" AND kind IN (")
                    .append(query.getKinds().stream().map(k -> "?").collect(Collectors.joining(",")))
                    .append(")");
            query.getKinds().forEach(k -> args.add(k.name()));
        }
        if (query.getMinImportance() != null && query.getMinImportance() > 0) {
            sql.append(" AND importance>=?");
            args.add(query.getMinImportance());
        }
        sql.append(" ORDER BY created_at ASC LIMIT ?");
        args.add(query.effectiveLimit());
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    @Override
    public long countActive(MemoryScope scope) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM orrery_memory_item "
                + "WHERE status='" + MemoryStatus.ACTIVE.name() + "' AND scope_type=? AND owner_id=?");
        List<Object> args = new ArrayList<>(List.of(scope.type().name(), scope.ownerId()));
        if (scope.tenantId() != null) {
            sql.append(" AND tenant_id=?");
            args.add(scope.tenantId());
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public void appendRevision(MemoryRevision revision) {
        jdbc.update("INSERT INTO orrery_memory_revision (revision_id, memory_id, op, before_json, "
                        + "after_json, actor_id, reason, at) VALUES (?,?,?,?,?,?,?,?)",
                Long.parseLong(revision.getRevisionId()), Long.parseLong(revision.getMemoryId()),
                revision.getOp().name(), revision.getBeforeJson(), revision.getAfterJson(),
                revision.getActorId(), revision.getReason(), millis(revision.getAt()));
    }

    @Override
    public List<MemoryRevision> history(String memoryId) {
        return jdbc.query("SELECT * FROM orrery_memory_revision WHERE memory_id=? ORDER BY at ASC",
                (rs, rowNum) -> MemoryRevision.builder()
                        .revisionId(String.valueOf(rs.getLong("revision_id")))
                        .memoryId(String.valueOf(rs.getLong("memory_id")))
                        .op(MemoryRevision.Op.valueOf(rs.getString("op")))
                        .beforeJson(rs.getString("before_json"))
                        .afterJson(rs.getString("after_json"))
                        .actorId(rs.getString("actor_id"))
                        .reason(rs.getString("reason"))
                        .at(instant(rs.getLong("at")))
                        .build(),
                Long.parseLong(memoryId));
    }

    @Override
    public void purgeRevisions(String memoryId) {
        jdbc.update("DELETE FROM orrery_memory_revision WHERE memory_id=?", Long.parseLong(memoryId));
    }

    @Override
    public int purgeTombstoned(Instant olderThan) {
        return jdbc.update("DELETE FROM orrery_memory_item WHERE status<>'"
                        + MemoryStatus.ACTIVE.name() + "' AND updated_at<?",
                olderThan.toEpochMilli());
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    /** 行映射。 */
    private MemoryItem map(ResultSet rs, int rowNum) throws SQLException {
        long lastAccessed = rs.getLong("last_accessed_at");
        return MemoryItem.builder()
                .memoryId(String.valueOf(rs.getLong("memory_id")))
                .scope(new MemoryScope(ScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("owner_id"), rs.getString("tenant_id")))
                .kind(MemoryKind.valueOf(rs.getString("kind")))
                .abstractText(rs.getString("abstract_text"))
                .content(rs.getString("content"))
                .sourceRef(rs.getString("source_ref"))
                .importance(rs.getInt("importance"))
                .status(MemoryStatus.valueOf(rs.getString("status")))
                .version(rs.getLong("version"))
                .accessCount(rs.getLong("access_count"))
                .createdAt(instant(rs.getLong("created_at")))
                .updatedAt(instant(rs.getLong("updated_at")))
                .lastAccessedAt(rs.wasNull() ? null : instant(lastAccessed))
                .provenance(new Provenance(null, rs.getString("source_ref"), null, false))
                .build();
    }

    /** Instant → epoch millis（null 安全）。 */
    private static long millis(Instant at) {
        return at == null ? 0L : at.toEpochMilli();
    }

    /** epoch millis → Instant。 */
    private static Instant instant(long millis) {
        return Instant.ofEpochMilli(millis);
    }
}
