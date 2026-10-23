package com.cy.milvus.support;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Auther: pcy
 * @Date: 2025/9/29 11:45
 * @Description:
 *  主要负责（v2 API）：
 *  - 扫描类注解（@Column, @VectorField, @TextField, @SparseVectorField, @BM25Function, @Index）
 *  - 生成 v2 API Schema（包含字段和 BM25 函数）
 *  - 收集索引信息
 *  - 缓存 Class -> SchemaResult
 *
 * 调用 buildSchema(MilvusClientV2, Class, sampleInstance) 返回 SchemaResult
 */
public class EnhancedAnnotationSchemaBuilder {

    // 缓存 Class -> SoftReference<SchemaResult>
    private static final Map<Class<?>, SoftReference<SchemaResult>> cacheV2 = new ConcurrentHashMap<>();

    private static final Gson gson = new Gson();

    private EnhancedAnnotationSchemaBuilder() {
        // 私有构造，防止实例化
    }

    /**
     * 构建 Milvus v2 API Schema（支持 BM25 函数）
     * @param schema 由 MilvusClientV2.createSchema() 创建的空 schema
     * @param clazz 带注解的实体类
     * @param sampleInstance optional 样本实例（用于从 runtime 值推断 vector dimension）
     * @return SchemaResult（包含填充后的 schema、索引参数列表）
     */
    public static SchemaResult buildSchema(CreateCollectionReq.CollectionSchema schema,
                                                Class<?> clazz,
                                                Object sampleInstance) {
        // 查缓存
        SoftReference<SchemaResult> ref = cacheV2.get(clazz);
        if (ref != null) {
            SchemaResult sr = ref.get();
            if (sr != null) {
                // 注意：缓存的是字段定义，需要重新填充到传入的 schema
                sr.applyToSchema(schema);
                return sr;
            }
            cacheV2.remove(clazz);
        }

        List<AddFieldReq> fieldReqs = new ArrayList<>();
        List<IndexParam> indexParams = new ArrayList<>();
        List<CreateCollectionReq.Function> functions = new ArrayList<>();
        boolean hasPrimary = false;

        // 第一遍扫描：收集字段和 BM25 函数定义
        Field[] fields = clazz.getDeclaredFields();
        Map<String, String> bm25InputToOutput = new LinkedHashMap<>(); // inputField -> outputField

        for (Field f : fields) {
            f.setAccessible(true);
            VectorField vf = f.getAnnotation(VectorField.class);
            SparseVectorField svf = f.getAnnotation(SparseVectorField.class);
            TextField tf = f.getAnnotation(TextField.class);
            Column col = f.getAnnotation(Column.class);
            Index idx = f.getAnnotation(Index.class);
            BM25Function bm25 = f.getAnnotation(BM25Function.class);

            // 1. 稠密向量字段
            if (vf != null && vf.declare()) {
                String name = !vf.name().isEmpty() ? vf.name() : camelToSnakeRegex(f.getName());
                int dim = vf.dimension();
                if (dim <= 0) {
                    dim = inferDimensionFromSample(f, sampleInstance);
                }
                if (dim <= 0) {
                    throw new IllegalStateException("向量字段 '" + name + "' 需要指定维度");
                }

                AddFieldReq fieldReq = AddFieldReq.builder()
                        .fieldName(name)
                        .dataType(DataType.FloatVector)
                        .dimension(dim)
                        .description(vf.description())
                        .build();
                fieldReqs.add(fieldReq);

                // 收集索引
                if (idx != null) {
                    IndexParam.IndexParamBuilder indexBuilder = IndexParam.builder()
                            .fieldName(name)
                            .indexType(mapIndexType(idx.indexType()))
                            .metricType(mapMetricType(idx.metricType()));
                    
                    // 解析额外参数
                    if (idx.params() != null && !idx.params().isEmpty()) {
                        Map<String, Object> extraParams = parseJsonToMap(idx.params());
                        indexBuilder.extraParams(extraParams);
                    }
                    indexParams.add(indexBuilder.build());
                }
                continue;
            }

            // 2. 稀疏向量字段（BM25 输出字段）
            if (svf != null && svf.declare()) {
                String name = !svf.name().isEmpty() ? svf.name() : camelToSnakeRegex(f.getName());
                
                AddFieldReq fieldReq = AddFieldReq.builder()
                        .fieldName(name)
                        .dataType(DataType.SparseFloatVector)
                        .description(svf.description())
                        .build();
                fieldReqs.add(fieldReq);

                // 收集 BM25 函数定义
                if (bm25 != null) {
                    String inputFieldName = camelToSnakeRegex(bm25.inputField());
                    bm25InputToOutput.put(inputFieldName, name);
                    
                    functions.add(CreateCollectionReq.Function.builder()
                            .name(bm25.name())
                            .functionType(FunctionType.BM25)
                            .inputFieldNames(Collections.singletonList(inputFieldName))
                            .outputFieldNames(Collections.singletonList(name))
                            .build());
                }

                // 稀疏向量索引
                if (idx != null) {
                    indexParams.add(IndexParam.builder()
                            .fieldName(name)
                            .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                            .metricType(IndexParam.MetricType.BM25)
                            .build());
                }
                continue;
            }

            // 3. 文本字段（支持 BM25 分析器）
            if (tf != null) {
                String name = !tf.name().isEmpty() ? tf.name() : camelToSnakeRegex(f.getName());
                
                AddFieldReq.AddFieldReqBuilder fieldBuilder = AddFieldReq.builder()
                        .fieldName(name)
                        .dataType(DataType.VarChar)
                        .maxLength(tf.maxLength())
                        .description(tf.description())
                        .enableAnalyzer(tf.enableAnalyzer())
                        .enableMatch(tf.enableMatch());
                
                // 设置分析器参数
                if (tf.enableAnalyzer() && tf.analyzerType() != null && !tf.analyzerType().isEmpty()) {
                    Map<String, Object> analyzerParams = new HashMap<>();
                    analyzerParams.put("type", tf.analyzerType());
                    fieldBuilder.analyzerParams(analyzerParams);
                }
                
                fieldReqs.add(fieldBuilder.build());
                continue;
            }

            // 4. 标量字段
            if (col != null && !col.asMeta()) {
                String name = !col.name().isEmpty() ? col.name() : camelToSnakeRegex(f.getName());
                
                if (col.primaryKey()) {
                    hasPrimary = true;
                    AddFieldReq.AddFieldReqBuilder pkBuilder = AddFieldReq.builder()
                            .fieldName(name)
                            .dataType(resolvePrimaryTypeV2(f))
                            .isPrimaryKey(true)
                            .autoID(col.autoId())
                            .description(col.description());
                    
                    if (String.class.equals(f.getType())) {
                        pkBuilder.maxLength(col.maxLength());
                    }
                    fieldReqs.add(pkBuilder.build());
                    continue;
                }
                
                AddFieldReq fieldReq = mapJavaTypeToAddFieldReq(f.getType(), name, col);
                fieldReqs.add(fieldReq);
                
                // 标量字段索引（用于过滤加速）
                if (col.index()) {
                    indexParams.add(IndexParam.builder()
                            .fieldName(name)
                            .indexType(IndexParam.IndexType.AUTOINDEX)
                            .build());
                }
            }
        }

        // 如果没有主键，添加默认主键
        if (!hasPrimary) {
            fieldReqs.addFirst(AddFieldReq.builder()
                    .fieldName("id")
                    .dataType(DataType.Int64)
                    .isPrimaryKey(true)
                    .autoID(true)
                    .build());
        }

        // 构建结果并填充 schema
        SchemaResult result = new SchemaResult(fieldReqs, indexParams, functions);
        result.applyToSchema(schema);
        
        // 放入缓存
        cacheV2.put(clazz, new SoftReference<>(result));
        return result;
    }

    /**
     * 从样本实例中推断向量维度
     */
    private static int inferDimensionFromSample(Field f, Object sampleInstance) {
        if (sampleInstance == null) return -1;
        try {
            Object val = f.get(sampleInstance);
            if (val == null) return -1;
            if (val instanceof float[]) return ((float[]) val).length;
            if (val instanceof Float[]) return ((Float[]) val).length;
            if (val instanceof List) return ((List<?>) val).size();
        } catch (IllegalAccessException e) {
            return -1;
        }
        return -1;
    }

    /**
     * 将 Java 类型映射到 v2 API AddFieldReq
     */
    private static AddFieldReq mapJavaTypeToAddFieldReq(Class<?> javaType, String name, Column col) {
        AddFieldReq.AddFieldReqBuilder builder = AddFieldReq.builder()
                .fieldName(name)
                .description(col.description())
                .isNullable(col.nullable());
        
        if (String.class.equals(javaType)) {
            return builder.dataType(DataType.VarChar).maxLength(Math.max(1, col.maxLength())).build();
        } else if (Long.class.equals(javaType) || long.class.equals(javaType)) {
            return builder.dataType(DataType.Int64).build();
        } else if (Integer.class.equals(javaType) || int.class.equals(javaType)) {
            return builder.dataType(DataType.Int32).build();
        } else if (Double.class.equals(javaType) || double.class.equals(javaType)) {
            return builder.dataType(DataType.Double).build();
        } else if (Float.class.equals(javaType) || float.class.equals(javaType)) {
            return builder.dataType(DataType.Float).build();
        } else if (Boolean.class.equals(javaType) || boolean.class.equals(javaType)) {
            return builder.dataType(DataType.Bool).build();
        } else {
            // 默认 JSON（Map/Object 类型）
            return builder.dataType(DataType.JSON).build();
        }
    }

    /**
     * 解析主键字段的数据类型（v2 API）
     */
    private static DataType resolvePrimaryTypeV2(Field f) {
        Class<?> t = f.getType();
        if (String.class.equals(t)) return DataType.VarChar;
        if (Long.class.equals(t) || long.class.equals(t)) return DataType.Int64;
        if (Integer.class.equals(t) || int.class.equals(t)) return DataType.Int64;
        return DataType.VarChar;
    }

    /**
     * 映射 v1 IndexType 到 v2 IndexType
     */
    private static IndexParam.IndexType mapIndexType(io.milvus.param.IndexType v1Type) {
        if (v1Type == null) return IndexParam.IndexType.AUTOINDEX;
        return switch (v1Type) {
            case HNSW -> IndexParam.IndexType.HNSW;
            case IVF_FLAT -> IndexParam.IndexType.IVF_FLAT;
            case IVF_SQ8 -> IndexParam.IndexType.IVF_SQ8;
            case IVF_PQ -> IndexParam.IndexType.IVF_PQ;
            case FLAT -> IndexParam.IndexType.FLAT;
            case DISKANN -> IndexParam.IndexType.DISKANN;
            case GPU_IVF_FLAT -> IndexParam.IndexType.GPU_IVF_FLAT;
            case GPU_IVF_PQ -> IndexParam.IndexType.GPU_IVF_PQ;
            default -> IndexParam.IndexType.AUTOINDEX;
        };
    }

    /**
     * 映射 v1 MetricType 到 v2 MetricType
     */
    private static IndexParam.MetricType mapMetricType(io.milvus.param.MetricType v1Type) {
        if (v1Type == null) return IndexParam.MetricType.COSINE;
        return switch (v1Type) {
            case L2 -> IndexParam.MetricType.L2;
            case IP -> IndexParam.MetricType.IP;
            case COSINE -> IndexParam.MetricType.COSINE;
            default -> IndexParam.MetricType.COSINE;
        };
    }

    /**
     * 解析 JSON 字符串到 Map，并将 Double 转换为 Integer（Milvus 索引参数要求整数）
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        try {
            Map<String, Object> map = gson.fromJson(json, Map.class);
            // Gson 默认将整数解析为 Double，需要转换回 Integer
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Double) {
                    Double d = (Double) value;
                    // 如果是整数（没有小数部分），转换为 Integer
                    if (d == Math.floor(d) && !Double.isInfinite(d)) {
                        result.put(entry.getKey(), d.intValue());
                    } else {
                        result.put(entry.getKey(), value);
                    }
                } else {
                    result.put(entry.getKey(), value);
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 把驼峰（camelCase 或 PascalCase）转成下划线命名（snake_case），全小写。
     * 示例： "myFieldName" → "my_field_name"; "URLValue" → "url_value"
     */
    public static String camelToSnakeRegex(String str) {
        if (str == null) return null;
        // 在一个小写字母后面接大写字母，插入下划线
        String s = str.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        // 处理连续大写 & 后面小写的情况（如 ABCName → ABC_Name → a_b_c_name）
        s = s.replaceAll("([A-Z])([A-Z][a-z])", "$1_$2");
        return s.toLowerCase();
    }

    /**
     * 把 list 转成符合 Milvus 插入期望的 List<JsonObject>
     * @param list 要转换的实体列表
     * @param enableDynamicField 如果 true，未声明字段会放到 meta_data；否则未声明字段会被忽略
     * @param includeNullValues 如果 true，则把显式的空值写成 JsonNull.INSTANCE（否则 omit）
     * @return List<JsonObject> 准备插入 Milvus 的数据行
     */
    public static List<JsonObject> toSnakeJsonListWithAnnotations(List<?> list, boolean enableDynamicField, boolean includeNullValues) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<JsonObject> out = new ArrayList<>(list.size());
        for (Object obj : list) {
            out.add(toSnakeJsonObjectWithAnnotations(obj, enableDynamicField, includeNullValues));
        }
        return out;
    }

    /**
     * 参数:
     *  - obj: 要转换的实体
     *  - enableDynamicField: 是否将未声明字段放到 meta_data
     *  - includeNullValues: 如果 true，则把显式的空值写成 JsonNull.INSTANCE（否则 omit）
     */
    public static JsonObject toSnakeJsonObjectWithAnnotations(Object obj,
                                                              boolean enableDynamicField,
                                                              boolean includeNullValues) {
        if (obj == null) return null;
        JsonObject root = new JsonObject();
        JsonObject meta = new JsonObject();

        Class<?> cls = obj.getClass();
        Field[] fields = cls.getDeclaredFields();
        Set<String> processedRawNames = new HashSet<>();

        for (Field f : fields) {
            f.setAccessible(true);
            Column col = f.getAnnotation(Column.class);
            VectorField vf = f.getAnnotation(VectorField.class);
            TextField tf = f.getAnnotation(TextField.class);
            SparseVectorField svf = f.getAnnotation(SparseVectorField.class);

            // 稀疏向量字段跳过（由 BM25 函数自动生成）
            if (svf != null) {
                processedRawNames.add(f.getName());
                continue;
            }

            // 主键 autoId 跳过
            boolean isPrimary = (col != null && col.primaryKey());
            boolean isAutoId = (col != null && col.autoId());
            if (isPrimary && isAutoId) {
                processedRawNames.add(f.getName());
                continue;
            }

            // 决定 raw name（注解优先），再转成 snake
            String rawName;
            if (col != null && col.name() != null && !col.name().isEmpty()) rawName = col.name();
            else if (vf != null && vf.name() != null && !vf.name().isEmpty()) rawName = vf.name();
            else if (tf != null && tf.name() != null && !tf.name().isEmpty()) rawName = tf.name();
            else rawName = f.getName();
            String snakeName = camelToSnakeRegex(rawName);

            // 读取值（反射优先）
            Object rawVal = null;
            try {
                rawVal = f.get(obj);
            } catch (IllegalAccessException ignored) {}

            // 决定目标位置（顶层 or meta_data）
            boolean forceTopLevel = (vf != null && vf.declare()) || (col != null && !col.asMeta()) || (tf != null);

            if (rawVal == null) {
                // null 值处理：默认 omit；如果 includeNullValues==true 则写 JsonNull.INSTANCE
                if (includeNullValues) {
                    if (forceTopLevel) {
                        root.add(snakeName, JsonNull.INSTANCE);
                    } else if (enableDynamicField) {
                        meta.add(snakeName, JsonNull.INSTANCE);
                    }
                }
                // 标记已处理并跳过
                processedRawNames.add(f.getName());
                continue;
            }

            // 非 null：转成 JsonElement 放入对应位置
            JsonElement valElem = gson.toJsonTree(rawVal);
            if (forceTopLevel) {
                root.add(snakeName, valElem);
            } else {
                if (enableDynamicField) {
                    meta.add(snakeName, valElem);
                }
                // 否则忽略未声明字段
            }
            processedRawNames.add(f.getName());
        }

        // 处理 gson 原始的 json keys（可能来自 getter 或 Lombok），但不重复已处理的字段
        JsonElement tree = gson.toJsonTree(obj);
        if (tree.isJsonObject()) {
            JsonObject orig = tree.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : orig.entrySet()) {
                String origKey = e.getKey();
                if (processedRawNames.contains(origKey)) continue;
                String snake = camelToSnakeRegex(origKey);
                JsonElement v = e.getValue();

                // 如果 v 是 JsonNull 或 null：同上策略
                boolean isNullElement = (v == null) || v.isJsonNull();
                if (isNullElement) {
                    if (includeNullValues && enableDynamicField) {
                        meta.add(snake, JsonNull.INSTANCE);
                    }
                } else {
                    if (enableDynamicField) {
                        meta.add(snake, v);
                    }
                }
            }
        }

        if (meta.size() > 0) {
            root.add("meta_data", meta);
        }
        return root;
    }
}
