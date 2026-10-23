// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.support;

import com.cy.orrery.memory.profile.ProfileItem;
import com.cy.orrery.memory.profile.ProfileStore;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用画像内存存储。
 *
 * @author pcy
 */
public class InMemoryProfileStore implements ProfileStore {

    /** 条目表。 */
    private final Map<String, ProfileItem> items = new LinkedHashMap<>();

    @Override
    public synchronized void save(ProfileItem item) {
        items.put(item.getItemId(), item);
    }

    @Override
    public synchronized Optional<ProfileItem> get(String itemId) {
        return Optional.ofNullable(items.get(itemId));
    }

    @Override
    public synchronized boolean update(ProfileItem item, long expectedVersion) {
        ProfileItem current = items.get(item.getItemId());
        if (current == null || current.getVersion() != expectedVersion) {
            return false;
        }
        items.put(item.getItemId(), item);
        return true;
    }

    @Override
    public synchronized List<ProfileItem> list(ProfileItem.OwnerType ownerType, String tenantId,
                                               String ownerId, String dimension,
                                               boolean includeInactive) {
        return items.values().stream()
                .filter(i -> i.getOwnerType() == ownerType)
                .filter(i -> i.getOwnerId().equals(ownerId))
                .filter(i -> tenantId == null || tenantId.equals(i.getTenantId()))
                .filter(i -> dimension == null || dimension.equals(i.getDimension()))
                .filter(i -> includeInactive || i.getStatus() == ProfileItem.Status.ACTIVE)
                .sorted(Comparator.comparing(ProfileItem::getCreatedAt))
                .toList();
    }

    @Override
    public synchronized int deleteByOwner(ProfileItem.OwnerType ownerType, String tenantId,
                                          String ownerId) {
        List<String> victims = items.values().stream()
                .filter(i -> i.getOwnerType() == ownerType)
                .filter(i -> i.getOwnerId().equals(ownerId))
                .filter(i -> tenantId == null || tenantId.equals(i.getTenantId()))
                .map(ProfileItem::getItemId)
                .toList();
        victims.forEach(items::remove);
        return victims.size();
    }
}
