package com.cy.textsearch.index;

import com.cy.textsearch.entity.SearchResult;
import com.cy.textsearch.index.support.HanLPAnalyzer;
import com.cy.textsearch.index.support.LuceneSearcher;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static com.cy.textsearch.index.LuceneConstants.*;

/**
 * Lucene索引管理器
 * <p>
 * 重构后的轻量级索引管理器，专注于核心的索引管理功能：
 * - 索引的创建、更新、删除操作
 * - 委托搜索功能给LuceneSearcher
 * - 管理索引目录和分析器
 * - 为MySQL存储方案提供高性能的全文检索能力
 * </p>
 *
 * <strong>设计原则：</strong>
 * - 单一职责：只负责索引管理，不处理查询构建和高亮
 * - 依赖注入：使用组件化设计，便于测试和维护
 * - 常量外化：所有常量提取到LuceneConstants中
 * - 线程安全：使用共享 IndexWriter 支持并发写入
 *
 * @author pcy
 * @date 2025年11月18日
 */
public class LuceneIndexManager {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexManager.class);

    /**
     * Lucene索引目录
     * 使用FSDirectory实现，支持本地文件系统存储
     */
    private final Directory indexDirectory;

    /**
     * 文本分析器
     * 用于分词和文本处理，确保索引和查询时的一致性
     */
    private final Analyzer analyzer;

    /**
     * 搜索执行器
     * 负责执行具体的搜索操作和结果处理
     */
    private final LuceneSearcher searcher;

    /**
     * 共享的 IndexWriter 实例
     * <p>
     * IndexWriter 本身是线程安全的，多个线程可以安全地并发调用
     * addDocument/updateDocument/deleteDocuments 等方法。
     * 但同一目录只能有一个 IndexWriter 实例，否则会报 LockObtainFailedException。
     * </p>
     */
    private volatile IndexWriter sharedWriter;

    /**
     * 构造函数
     * <p>
     * 初始化索引管理器的核心组件：
     * 1. 创建或打开索引目录
     * 2. 初始化文本分析器
     * 3. 注入搜索组件依赖
     * </p>
     *
     * @param indexPathStr 索引文件存储路径，支持配置注入
     * @param searcher     搜索执行器，处理具体搜索逻辑
     */
    public LuceneIndexManager(String indexPathStr,
                              LuceneSearcher searcher, HanLPAnalyzer hanLPAnalyzer) {
        try {
            // 第一步：解析并创建索引路径
            Path indexPath = Paths.get(indexPathStr);
            log.debug("解析索引路径: {}", indexPath.toAbsolutePath());

            // 第二步：确保索引目录存在，如果不存在则创建
            if (!Files.exists(indexPath)) {
                Files.createDirectories(indexPath);
                log.info("创建索引目录: {}", indexPath.toAbsolutePath());
            }

            // 第三步：打开文件系统索引目录
            this.indexDirectory = FSDirectory.open(indexPath);

            // 第四步：初始化依赖组件
            this.analyzer = hanLPAnalyzer;
            this.searcher = searcher;

            // 第五步：初始化共享 IndexWriter
            this.sharedWriter = createIndexWriter();

            log.info("Lucene索引管理器初始化成功 - 索引路径: {}, 分析器: {}, 支持并发写入: true",
                    indexPathStr, analyzer.getClass().getSimpleName());

        } catch (IOException e) {
            log.error("初始化索引目录失败: path={}", indexPathStr, e);
            throw new RuntimeException("Lucene索引管理器初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 资源清理
     * <p>在 Spring 容器关闭时自动关闭 IndexWriter</p>
     */
    public void close() {
        if (sharedWriter != null) {
            try {
                sharedWriter.close();
                log.info("IndexWriter 已关闭");
            } catch (IOException e) {
                log.error("关闭 IndexWriter 失败", e);
            }
        }
    }

    /**
     * 添加文档到索引
     * <p>
     * 文档索引过程说明：
     * 1. 创建Lucene Document对象
     * 2. 添加各种类型的字段（ID、标题、内容、词项、关键词）
     * 3. 根据权重重复添加关键词以提升相关性
     * 4. 使用updateDocument实现增量更新
     * 5. 提交更改并持久化到磁盘
     * </p>
     *
     * @param docId      文档唯一标识ID，用于更新和删除操作
     * @param knowledgeId 知识库ID，用于过滤和批量删除
     * @param title      文档标题，存储并建立索引用于搜索
     * @param content    文档主要内容，存储并建立索引
     * @param validTerms HanLP分析后的有效词项列表，仅建立索引不存储
     * @param keywords   关键词及其权重映射，权重影响相关性评分
     */
    public void addDocument(String docId, String knowledgeId, String title, String content,
                            List<String> validTerms, Map<String, Double> keywords) {
        // 参数有效性检查
        if (docId == null) {
            throw new IllegalArgumentException("文档ID不能为空");
        }

        try {
            // 获取共享 IndexWriter
            IndexWriter writer = getSharedWriter();

            // 第一步：创建Lucene文档对象
            Document doc = new Document();

            // 第二步：添加文档ID字段（StringField，精确匹配，不分词）
            doc.add(new StringField(FIELD_DOC_ID, String.valueOf(docId), Field.Store.YES));
            log.debug("添加文档ID字段: {}", docId);

            // 第三步：添加知识库ID字段（用于过滤和批量删除）
            if (StringUtils.isNotBlank(knowledgeId)) {
                doc.add(new StringField(FIELD_KNOWLEDGE_ID, knowledgeId, Field.Store.YES));
                log.debug("添加知识库ID字段: {}", knowledgeId);
            }

            // 第四步：添加标题字段（如果存在）
            if (StringUtils.isNotBlank(title)) {
                doc.add(new TextField(FIELD_TITLE, title, Field.Store.YES));
                log.debug("添加标题字段: 长度={}", title.length());
            }

            // 第五步：添加内容字段（如果存在）
            if (StringUtils.isNotBlank(content)) {
                doc.add(new TextField(FIELD_CONTENT, content, Field.Store.YES));
                log.debug("添加内容字段: 长度={}", content.length());
            }

            // 第五步：添加有效词项字段（仅索引，不存储，用于搜索召回）
            if (validTerms != null && !validTerms.isEmpty()) {
                FieldType termFieldType = new FieldType(TextField.TYPE_NOT_STORED);
                termFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS); // 支持位置信息

                String termsText = String.join(" ", validTerms); // 用空格连接词项
                doc.add(new Field(FIELD_VALID_TERMS, termsText, termFieldType));
                log.debug("添加有效词项字段: 词项数={}", validTerms.size());

                // 文档上传分词效果日志
                log.info("文档上传分词结果 [docId={}]:", docId);
                log.info("  - 标题: {}", title != null ? title.substring(0, Math.min(title.length(), 100)) + "..." : "无");
                log.info("  - 内容长度: {} 字符", content != null ? content.length() : 0);
                log.info("  - 分词结果: {} 个有效词汇", validTerms.size());
                log.info("  - 前10个词汇: {}", validTerms.stream().limit(10).collect(Collectors.toList()));
                if (validTerms.size() > 10) {
                    log.info("  - 更多词汇: ...(还有{}个)", validTerms.size() - 10);
                }
            }

            // 第六步：添加关键词字段（根据权重重复添加以提升评分）
            if (keywords != null && !keywords.isEmpty()) {
                FieldType keywordFieldType = new FieldType(TextField.TYPE_NOT_STORED);
                keywordFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);

                int totalKeywordInstances = 0;
                for (Map.Entry<String, Double> entry : keywords.entrySet()) {
                    String keyword = entry.getKey();
                    Double weight = entry.getValue();

                    // 跳过无效关键词
                    if (StringUtils.isBlank(keyword) || weight == null || weight <= 0) {
                        continue;
                    }

                    // 根据权重计算重复次数（权重越高，重复越多，评分越高）
                    int repeatCount = Math.max(1, (int) Math.round(weight * KEYWORD_WEIGHT_MULTIPLIER));

                    // 重复添加关键词字段
                    for (int i = 0; i < repeatCount; i++) {
                        doc.add(new Field(FIELD_KEYWORDS, keyword, keywordFieldType));
                        totalKeywordInstances++;
                    }
                }
                log.debug("添加关键词字段: 原始数={}, 实例数={}", keywords.size(), totalKeywordInstances);
            }

            // 第七步：执行文档更新操作（如果存在则更新，不存在则添加）
            Term docIdTerm = new Term(FIELD_DOC_ID, String.valueOf(docId));
            writer.updateDocument(docIdTerm, doc);

            // 第八步：提交索引（创建提交点，使索引可被搜索）
            writer.commit();

            log.info("文档索引操作成功: docId={}, 有标题={}, 有内容={}, 词项数={}, 关键词数={}",
                    docId, title != null, content != null,
                    validTerms != null ? validTerms.size() : 0,
                    keywords != null ? keywords.size() : 0);

        } catch (IOException e) {
            log.error("文档索引操作失败: docId={}, error={}", docId, e.getMessage(), e);
            throw new RuntimeException("添加文档到索引失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从索引中删除文档
     * <p>
     * 删除过程说明：
     * 1. 创建IndexWriter实例
     * 2. 根据文档ID构建删除条件
     * 3. 执行删除操作
     * 4. 提交更改确保删除生效
     * </p>
     *
     * @param docId 要删除的文档ID，必须非空
     * @throws IllegalArgumentException 当docId为null时
     * @throws RuntimeException         当删除操作失败时
     */
    public void deleteDocument(String docId) {
        // 参数有效性检查
        if (docId == null) {
            throw new IllegalArgumentException("删除操作的文档ID不能为空");
        }

        try {
            // 获取共享 IndexWriter
            IndexWriter writer = getSharedWriter();

            // 第一步：构建删除条件（基于文档ID精确匹配）
            Term docIdTerm = new Term(FIELD_DOC_ID, String.valueOf(docId));

            // 第二步：执行删除操作（可能删除多个匹配的文档）
            long deletedCount = writer.deleteDocuments(docIdTerm);

            // 第三步：提交索引
            writer.commit();

            if (deletedCount > 0) {
                log.info("文档删除成功: docId={}, 删除数量={}", docId, deletedCount);
            } else {
                log.warn("文档删除操作完成但未找到匹配文档: docId={}", docId);
            }

        } catch (IOException e) {
            log.error("文档删除操作失败: docId={}, error={}", docId, e.getMessage(), e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据知识库ID批量删除文档索引
     *
     * @param knowledgeId 知识库ID
     * @return 删除的文档数量
     */
    public long deleteByKnowledgeId(String knowledgeId) {
        if (StringUtils.isBlank(knowledgeId)) {
            throw new IllegalArgumentException("知识库ID不能为空");
        }

        try {
            IndexWriter writer = getSharedWriter();
            Term knowledgeIdTerm = new Term(FIELD_KNOWLEDGE_ID, knowledgeId);
            long deletedCount = writer.deleteDocuments(knowledgeIdTerm);
            writer.commit();

            log.info("批量删除知识库索引成功: knowledgeId={}, 删除数量={}", knowledgeId, deletedCount);
            return deletedCount;

        } catch (IOException e) {
            log.error("批量删除知识库索引失败: knowledgeId={}, error={}", knowledgeId, e.getMessage(), e);
            throw new RuntimeException("批量删除知识库索引失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据知识文档标识集合批量删除文档索引
     *
     * @param docIds 知识文档标识集合
     * @return 删除的文档数量
     */
    public long deleteByDocIds(List<Long> docIds) {
        // 参数有效性检查
        if (docIds == null || docIds.isEmpty()) {
            throw new IllegalArgumentException("删除操作的文档ID列表不能为空");
        }

        try {
            // 获取共享 IndexWriter
            IndexWriter writer = getSharedWriter();

            // 第一步：构建删除条件数组（过滤空值并转换为 Term）
            Term[] deleteTerms = docIds.stream()
                    .filter(Objects::nonNull)
                    .map(id -> new Term(FIELD_DOC_ID, String.valueOf(id)))
                    .toArray(Term[]::new);

            if (deleteTerms.length == 0) {
                log.warn("文档删除操作完成但未找到有效文档ID");
                return 0L;
            }

            // 第二步：执行批量删除操作
            long deletedCount = writer.deleteDocuments(deleteTerms);

            // 第三步：提交索引
            writer.commit();

            // 日志记录（限制列表长度避免日志过大）
            String docIdLog = docIds.size() > 10
                    ? docIds.subList(0, 10) + "...(共" + docIds.size() + "个)"
                    : docIds.toString();

            if (deletedCount > 0) {
                log.info("批量文档删除成功: docIds={}, 删除数量={}", docIdLog, deletedCount);
            } else {
                log.warn("文档删除操作完成但未找到匹配文档: docIds={}", docIdLog);
            }
            return deletedCount;
        } catch (IOException e) {
            log.error("文档删除操作失败: docIds={}, error={}",
                    docIds.size() > 5 ? docIds.subList(0, 5) + "..." : docIds,
                    e.getMessage(), e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行全文搜索
     * <p>
     * 搜索功能委托说明：
     * 本方法将搜索请求委托给LuceneSearcher组件处理，
     * 保持LuceneIndexManager的单一职责原则（仅负责索引管理）。
     * LuceneSearcher负责复杂的查询构建、执行和结果处理逻辑。
     * </p>
     *
     * @param queryText        用户输入的查询文本
     * @param topK             返回的最大结果数，用于控制性能和响应时间
     * @param enableHighlight  是否启用高亮显示功能
     * @param highlightPreTag  高亮前缀标签，null时使用默认值
     * @param highlightPostTag 高亮后缀标签，null时使用默认值
     * @param knowledgeIds     知识库ID集合，用于过滤搜索范围，null或空集合表示不过滤
     * @return 搜索结果列表，包含文档ID、评分和高亮片段
     */
    public List<SearchResult> search(String queryText,
                                     int topK,
                                     boolean enableHighlight,
                                     String highlightPreTag,
                                     String highlightPostTag,
                                     Collection<String> knowledgeIds) {
        // 参数有效性检查
        if (StringUtils.isBlank(queryText)) {
            log.debug("查询文本为空，返回空结果");
            return new ArrayList<>();
        }

        if (topK <= 0) {
            log.debug("topK值无效: {}, 返回空结果", topK);
            return new ArrayList<>();
        }

        // 委托给搜索器执行具体的搜索逻辑
        log.debug("委托搜索请求: queryText='{}', topK={}, highlight={}",
                queryText, topK, enableHighlight);

        return searcher.search(indexDirectory, queryText, topK,
                enableHighlight, highlightPreTag, highlightPostTag, knowledgeIds);
    }

    /**
     * 获取共享的 IndexWriter 实例
     * <p>
     * 使用双重检查锁定模式确保线程安全的懒加载。
     * 如果 IndexWriter 被关闭，会自动重新创建。
     * </p>
     *
     * @return 共享的 IndexWriter 实例
     * @throws IOException 当无法创建 IndexWriter 时
     */
    private IndexWriter getSharedWriter() throws IOException {
        if (sharedWriter == null || !sharedWriter.isOpen()) {
            synchronized (this) {
                if (sharedWriter == null || !sharedWriter.isOpen()) {
                    sharedWriter = createIndexWriter();
                    log.info("重新创建共享 IndexWriter");
                }
            }
        }
        return sharedWriter;
    }

    /**
     * 创建索引写入器
     * <p>
     * IndexWriter配置说明：
     * - OpenMode.CREATE_OR_APPEND: 如果索引存在则追加，不存在则创建
     * - 使用统一的分析器确保索引和查询的一致性
     * - 采用默认的索引策略（合并策略、缓存策略等）
     * </p>
     *
     * @return 配置好的IndexWriter实例
     * @throws IOException 当无法创建或打开索引时
     */
    private IndexWriter createIndexWriter() throws IOException {
        // 第一步：创建IndexWriter配置
        IndexWriterConfig config = new IndexWriterConfig(analyzer);

        // 第二步：设置打开模式（创建或追加）
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        // 第三步：创建并返回IndexWriter实例
        IndexWriter writer = new IndexWriter(indexDirectory, config);

        log.debug("创建IndexWriter成功: 模式={}, 分析器={}",
                config.getOpenMode(), analyzer.getClass().getSimpleName());

        return writer;
    }

}