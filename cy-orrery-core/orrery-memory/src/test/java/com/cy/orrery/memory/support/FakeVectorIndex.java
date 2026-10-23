// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.support;

import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.VectorIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用向量索引（内存余弦相似度）。
 *
 * @author pcy
 */
public class FakeVectorIndex implements VectorIndex {

    /** 向量表。 */
    private final Map<String, float[]> vectors = new LinkedHashMap<>();

    /** 本体表（标量过滤来源）。 */
    private final Map<String, MemoryItem> items = new LinkedHashMap<>();

    @Override
    public void upsert(String memoryId, float[] vector, MemoryItem item) {
        vectors.put(memoryId, vector);
        items.put(memoryId, item);
    }

    @Override
    public List<VectorHit> search(float[] vector, MemoryQuery filter, int topK) {
        List<VectorHit> hits = new ArrayList<>();
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            MemoryItem item = items.get(e.getKey());
            if (item == null || !filter.effectiveStatuses().contains(item.getStatus())) {
                continue;
            }
            if (filter.getScopeType() != null && filter.getScopeType() != item.getScope().type()) {
                continue;
            }
            if (filter.getOwnerId() != null && !filter.getOwnerId().equals(item.getScope().ownerId())) {
                continue;
            }
            if (filter.getTenantId() != null && !filter.getTenantId().equals(item.getScope().tenantId())) {
                continue;
            }
            if (filter.getKinds() != null && !filter.getKinds().isEmpty()
                    && !filter.getKinds().contains(item.getKind())) {
                continue;
            }
            hits.add(new VectorHit(e.getKey(), cosine(vector, e.getValue())));
        }
        hits.sort(Comparator.comparingDouble(VectorHit::similarity).reversed());
        return hits.stream().limit(topK).toList();
    }

    @Override
    public void delete(String memoryId) {
        vectors.remove(memoryId);
        items.remove(memoryId);
    }

    @Override
    public boolean available() {
        return true;
    }

    /** 余弦相似度。 */
    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
