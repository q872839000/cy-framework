// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.milvus.core.MilvusClientManager;
import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.VectorIndex;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milvus 向量索引（L2/L1 的唯一召回通道）。
 * <p>
 * Collection 按 schema 与维度隔离：{@code orrery_memory_l2_{dim}}
 * （schema 变更即更换集合名前缀，强制新建 collection 并全量重建，避免静默字段错配）。
 * 标量过滤（tenant/scope_type/owner_id/kind/status）建索引，
 * {@link MemoryQuery} 的全部过滤语义在 Milvus 表达式内生效——多租户隔离与
 * 召回正确性在引擎层保证，不做召回后过滤。
 * 初始化惰性化：首次写/查时双重检查锁建集合，Milvus 未就绪不阻断 Bean 装配。
 * </p>
 *
 * @author pcy
 */
public class MilvusMemoryVectorIndex implements VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(MilvusMemoryVectorIndex.class);

    /** 集合名前缀（schema 版本内嵌；前缀变更即强制重建集合）。 */
    private static final String COLLECTION_PREFIX = "orrery_memory_l2_";

    /** 字段：记忆 ID（主键）。 */
    private static final String F_ID = "memory_id";

    /** 字段：租户。 */
    private static final String F_TENANT = "tenant_id";

    /** 字段：归属类型。 */
    private static final String F_SCOPE = "scope_type";

    /** 字段：属主。 */
    private static final String F_OWNER = "owner_id";

    /** 字段：内容类型。 */
    private static final String F_KIND = "kind";

    /** 字段：状态。 */
    private static final String F_STATUS = "status";

    /** 字段：向量。 */
    private static final String F_VECTOR = "embedding";

    /** Milvus 客户端管理器（cy-common-milvus）。 */
    private final MilvusClientManager clientManager;

    /** 向量维度。 */
    private final int dimension;

    /** 集合名（前缀 + 维度）。 */
    private final String collectionName;

    /** 惰性初始化标记（双重检查锁）。 */
    private volatile boolean collectionReady;

    /**
     * 构造 Milvus 向量索引（集合按维度隔离，首次写/查时惰性建集合）。
     *
     * @param clientManager Milvus 客户端管理器
     * @param dimension     向量维度（必须为正，由 embedding 模型决定）
     */
    public MilvusMemoryVectorIndex(MilvusClientManager clientManager, int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("向量维度必须为正: " + dimension);
        }
        this.clientManager = clientManager;
        this.dimension = dimension;
        this.collectionName = COLLECTION_PREFIX + dimension;
    }

    @Override
    public void upsert(String memoryId, float[] vector, MemoryItem item) {
        ensureCollection();
        JsonObject row = new JsonObject();
        row.addProperty(F_ID, memoryId);
        row.addProperty(F_TENANT, item.getScope().tenantId() == null ? "" : item.getScope().tenantId());
        row.addProperty(F_SCOPE, item.getScope().type().name());
        row.addProperty(F_OWNER, item.getScope().ownerId());
        row.addProperty(F_KIND, item.getKind().name());
        row.addProperty(F_STATUS, item.getStatus().name());
        row.add(F_VECTOR, new JsonArray());
        for (float v : vector) {
            row.getAsJsonArray(F_VECTOR).add(v);
        }
        clientManager.getClient().upsert(UpsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(row))
                .build());
    }

    @Override
    public List<VectorHit> search(float[] vector, MemoryQuery filter, int topK) {
        ensureCollection();
        SearchResp resp = clientManager.getClient().search(SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(new FloatVec(vector)))
                .topK(topK)
                .filter(buildFilter(filter))
                .build());
        List<VectorHit> hits = new ArrayList<>();
        if (resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return hits;
        }
        for (SearchResp.SearchResult result : resp.getSearchResults().get(0)) {
            float score = result.getScore() == null ? 0f : result.getScore();
            hits.add(new VectorHit(String.valueOf(result.getId()), Math.max(0, score)));
        }
        return hits;
    }

    @Override
    public void delete(String memoryId) {
        ensureCollection();
        clientManager.getClient().delete(DeleteReq.builder()
                .collectionName(collectionName)
                .filter(F_ID + " == " + stringLiteral(memoryId))
                .build());
    }

    @Override
    public boolean available() {
        return true;
    }

    /**
     * 构造 Milvus 过滤表达式（全部过滤语义在引擎层生效）。
     *
     * @param filter 查询条件
     * @return 表达式
     */
    private String buildFilter(MemoryQuery filter) {
        StringBuilder expr = new StringBuilder(F_STATUS + " in [");
        List<String> statuses = new ArrayList<>();
        for (var s : filter.effectiveStatuses()) {
            statuses.add(stringLiteral(s.name()));
        }
        expr.append(String.join(",", statuses)).append(']');
        if (filter.getTenantId() != null) {
            expr.append(" and ").append(F_TENANT).append(" == ").append(stringLiteral(filter.getTenantId()));
        }
        if (filter.getScopeType() != null) {
            expr.append(" and ").append(F_SCOPE).append(" == ").append(stringLiteral(filter.getScopeType().name()));
        }
        if (filter.getOwnerId() != null) {
            expr.append(" and ").append(F_OWNER).append(" == ").append(stringLiteral(filter.getOwnerId()));
        }
        if (filter.getKinds() != null && !filter.getKinds().isEmpty()) {
            List<String> kinds = new ArrayList<>();
            filter.getKinds().forEach(k -> kinds.add(stringLiteral(k.name())));
            expr.append(" and ").append(F_KIND).append(" in [").append(String.join(",", kinds)).append(']');
        }
        return expr.toString();
    }

    /**
     * 转义为 Milvus 表达式字符串字面量（tenantId / ownerId 等外部可控值必须经此
     * 进入表达式——直拼会被 {@code "} 截断表达式，构成注入与跨租户过滤逃逸）。
     *
     * @param value 原始值
     * @return 带引号的安全字面量
     */
    static String stringLiteral(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** 惰性建集合（双重检查锁）。 */
    private void ensureCollection() {
        if (collectionReady) {
            return;
        }
        synchronized (this) {
            if (collectionReady) {
                return;
            }
            Boolean exists = clientManager.getClient().hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build());
            if (Boolean.FALSE.equals(exists)) {
                createCollection();
            }
            collectionReady = true;
            log.info("Milvus 记忆向量集合就绪: {}", collectionName);
        }
    }

    /** 建集合（标量字段全索引，向量 COSINE/AUTOINDEX）。 */
    private void createCollection() {
        CreateCollectionReq.CollectionSchema schema = clientManager.getClient().createSchema();
        schema.addField(AddFieldReq.builder().fieldName(F_ID)
                .dataType(DataType.VarChar).isPrimaryKey(Boolean.TRUE).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName(F_TENANT)
                .dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName(F_SCOPE)
                .dataType(DataType.VarChar).maxLength(16).build());
        schema.addField(AddFieldReq.builder().fieldName(F_OWNER)
                .dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName(F_KIND)
                .dataType(DataType.VarChar).maxLength(16).build());
        schema.addField(AddFieldReq.builder().fieldName(F_STATUS)
                .dataType(DataType.VarChar).maxLength(16).build());
        schema.addField(AddFieldReq.builder().fieldName(F_VECTOR)
                .dataType(DataType.FloatVector).dimension(dimension).build());

        List<IndexParam> indexes = new ArrayList<>();
        for (String field : List.of(F_TENANT, F_SCOPE, F_OWNER, F_KIND, F_STATUS)) {
            indexes.add(IndexParam.builder().fieldName(field)
                    .indexType(IndexParam.IndexType.AUTOINDEX).build());
        }
        indexes.add(IndexParam.builder().fieldName(F_VECTOR)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE).build());

        clientManager.getClient().createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexes)
                .build());
    }
}
