// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 画像条目 MySQL 存储（表：orrery_memory_profile_item，见 schema.sql）。
 *
 * @author pcy
 */
public class JdbcProfileStore implements ProfileStore {

    /** JDBC。 */
    private final JdbcTemplate jdbc;

    /** 事务。 */
    private final TransactionTemplate tx;

    /**
     * 构造画像条目存储。
     *
     * @param jdbc JDBC
     * @param tx   事务模板
     */
    public JdbcProfileStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void save(ProfileItem item) {
        jdbc.update("INSERT INTO orrery_memory_profile_item "
                        + "(item_id, owner_type, owner_id, tenant_id, dimension, value, inferred, "
                        + "evidence, status, version, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                Long.parseLong(item.getItemId()), item.getOwnerType().name(), item.getOwnerId(),
                item.getTenantId(), item.getDimension(), item.getValue(), item.isInferred(),
                item.getEvidence(), item.getStatus().name(), item.getVersion(),
                millis(item.getCreatedAt()), millis(item.getUpdatedAt()));
    }

    @Override
    public Optional<ProfileItem> get(String itemId) {
        List<ProfileItem> hits = jdbc.query("SELECT * FROM orrery_memory_profile_item WHERE item_id=?",
                this::map, Long.parseLong(itemId));
        return hits.stream().findFirst();
    }

    @Override
    public boolean update(ProfileItem item, long expectedVersion) {
        int updated = jdbc.update("UPDATE orrery_memory_profile_item SET value=?, inferred=?, "
                        + "evidence=?, status=?, version=?, updated_at=? "
                        + "WHERE item_id=? AND version=?",
                item.getValue(), item.isInferred(), item.getEvidence(), item.getStatus().name(),
                item.getVersion(), millis(item.getUpdatedAt()),
                Long.parseLong(item.getItemId()), expectedVersion);
        return updated == 1;
    }

    @Override
    public List<ProfileItem> list(ProfileItem.OwnerType ownerType, String tenantId, String ownerId,
                                  String dimension, boolean includeInactive) {
        StringBuilder sql = new StringBuilder("SELECT * FROM orrery_memory_profile_item "
                + "WHERE owner_type=? AND owner_id=?");
        List<Object> args = new ArrayList<>(List.of(ownerType.name(), ownerId));
        if (tenantId != null) {
            sql.append(" AND tenant_id=?");
            args.add(tenantId);
        }
        if (dimension != null) {
            sql.append(" AND dimension=?");
            args.add(dimension);
        }
        if (!includeInactive) {
            sql.append(" AND status='" + ProfileItem.Status.ACTIVE.name() + "'");
        }
        sql.append(" ORDER BY created_at ASC");
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    @Override
    public int deleteByOwner(ProfileItem.OwnerType ownerType, String tenantId, String ownerId) {
        Integer deleted = tx.execute(status -> {
            StringBuilder sql = new StringBuilder("DELETE FROM orrery_memory_profile_item "
                    + "WHERE owner_type=? AND owner_id=?");
            List<Object> args = new ArrayList<>(List.of(ownerType.name(), ownerId));
            if (tenantId != null) {
                sql.append(" AND tenant_id=?");
                args.add(tenantId);
            }
            return jdbc.update(sql.toString(), args.toArray());
        });
        return deleted == null ? 0 : deleted;
    }

    /** 行映射。 */
    private ProfileItem map(ResultSet rs, int rowNum) throws SQLException {
        return ProfileItem.builder()
                .itemId(String.valueOf(rs.getLong("item_id")))
                .ownerType(ProfileItem.OwnerType.valueOf(rs.getString("owner_type")))
                .ownerId(rs.getString("owner_id"))
                .tenantId(rs.getString("tenant_id"))
                .dimension(rs.getString("dimension"))
                .value(rs.getString("value"))
                .inferred(rs.getBoolean("inferred"))
                .evidence(rs.getString("evidence"))
                .status(ProfileItem.Status.valueOf(rs.getString("status")))
                .version(rs.getLong("version"))
                .createdAt(instant(rs.getLong("created_at")))
                .updatedAt(instant(rs.getLong("updated_at")))
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
