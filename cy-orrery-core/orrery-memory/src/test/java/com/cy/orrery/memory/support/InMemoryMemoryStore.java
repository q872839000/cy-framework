// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.support;

import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryRevision;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.MemoryStatus;
import com.cy.orrery.memory.api.MemoryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用内存本体存储。
 *
 * @author pcy
 */
public class InMemoryMemoryStore implements MemoryStore {

    /** 条目表。 */
    private final Map<String, MemoryItem> items = new LinkedHashMap<>();

    /** 审计链表。 */
    private final Map<String, List<MemoryRevision>> revisions = new LinkedHashMap<>();

    @Override
    public synchronized void save(MemoryItem item) {
        items.put(item.getMemoryId(), item);
    }

    @Override
    public synchronized Optional<MemoryItem> get(String memoryId) {
        return Optional.ofNullable(items.get(memoryId));
    }

    @Override
    public synchronized boolean update(MemoryItem item, long expectedVersion) {
        MemoryItem current = items.get(item.getMemoryId());
        if (current == null || current.getVersion() != expectedVersion) {
            return false;
        }
        items.put(item.getMemoryId(), item);
        return true;
    }

    @Override
    public synchronized void recordAccess(String memoryId, Instant accessedAt) {
        MemoryItem current = items.get(memoryId);
        if (current == null) {
            return;
        }
        MemoryItem after = current.copy();
        after.setAccessCount(current.getAccessCount() + 1);
        after.setLastAccessedAt(accessedAt);
        items.put(memoryId, after);
    }

    @Override
    public synchronized void delete(String memoryId) {
        items.remove(memoryId);
    }

    @Override
    public synchronized List<MemoryItem> recall(MemoryQuery query) {
        List<MemoryItem> out = new ArrayList<>();
        for (MemoryItem item : items.values()) {
            if (!query.effectiveStatuses().contains(item.getStatus())) {
                continue;
            }
            if (query.getTenantId() != null && !query.getTenantId().equals(item.getScope().tenantId())) {
                continue;
            }
            if (query.getScopeType() != null && query.getScopeType() != item.getScope().type()) {
                continue;
            }
            if (query.getOwnerId() != null && !query.getOwnerId().equals(item.getScope().ownerId())) {
                continue;
            }
            if (query.getKinds() != null && !query.getKinds().isEmpty()
                    && !query.getKinds().contains(item.getKind())) {
                continue;
            }
            if (query.getMinImportance() != null && query.getMinImportance() > 0
                    && item.getImportance() < query.getMinImportance()) {
                continue;
            }
            out.add(item);
        }
        out.sort(Comparator.comparing(MemoryItem::getCreatedAt));
        return out.stream().limit(query.effectiveLimit()).toList();
    }

    @Override
    public synchronized long countActive(MemoryScope scope) {
        return items.values().stream()
                .filter(i -> i.getStatus() == MemoryStatus.ACTIVE)
                .filter(i -> i.getScope().type() == scope.type())
                .filter(i -> i.getScope().ownerId().equals(scope.ownerId()))
                .filter(i -> scope.tenantId() == null || scope.tenantId().equals(i.getScope().tenantId()))
                .count();
    }

    @Override
    public synchronized void appendRevision(MemoryRevision revision) {
        revisions.computeIfAbsent(revision.getMemoryId(), k -> new ArrayList<>()).add(revision);
    }

    @Override
    public synchronized List<MemoryRevision> history(String memoryId) {
        return List.copyOf(revisions.getOrDefault(memoryId, List.of()));
    }

    @Override
    public synchronized void purgeRevisions(String memoryId) {
        revisions.remove(memoryId);
    }

    @Override
    public synchronized int purgeTombstoned(Instant olderThan) {
        List<String> victims = items.values().stream()
                .filter(i -> i.getStatus() != MemoryStatus.ACTIVE)
                .filter(i -> i.getUpdatedAt() != null && i.getUpdatedAt().isBefore(olderThan))
                .map(MemoryItem::getMemoryId)
                .toList();
        victims.forEach(items::remove);
        return victims.size();
    }
}
