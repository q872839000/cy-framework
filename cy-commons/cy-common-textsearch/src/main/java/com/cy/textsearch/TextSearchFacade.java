package com.cy.textsearch;

import com.cy.textsearch.config.TextAnalysisProperties;
import com.cy.textsearch.entity.SearchResult;
import com.cy.textsearch.entity.TextAnalysisResult;
import com.cy.textsearch.index.LuceneIndexManager;
import com.cy.textsearch.internal.TextSearchComponentFactory;
import com.cy.textsearch.service.HanLPTextAnalysisService;
import com.cy.textsearch.vo.TextSearchVO;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 索引检索门面
 * <p>
 * 提供统一的分词索引建立功能。
 * </p>
 *
 * @author pcy
 */
public class TextSearchFacade {

    private static final Logger log = LoggerFactory.getLogger(TextSearchFacade.class);

    private final LuceneIndexManager luceneIndexManager;

    private final HanLPTextAnalysisService hanLPTextAnalysisService;

    /**
     * 主构造函数 - 通过配置属性创建（推荐）
     * <p>
     * 内部使用工厂模式创建所有组件，对外只暴露此门面类。
     * </p>
     *
     * @param properties 文本分析配置属性
     */
    public TextSearchFacade(TextAnalysisProperties properties) {
        TextSearchComponentFactory factory = new TextSearchComponentFactory(properties);
        this.luceneIndexManager = factory.getIndexManager();
        this.hanLPTextAnalysisService = factory.getAnalysisService();
        log.info("TextSearchFacade初始化完成 - 索引路径: {}", properties.getIndexPath());
    }

    /**
     * 创建本地LuceneIndex索引
     * @param docId 文档标识
     * @param knowledgeId 知识库ID
     * @param docName 文档名称
     * @param contentText 文档内容
     */
    public Boolean createLuceneIndex(String docId, String knowledgeId, String docName, String contentText) {
        StringBuilder fullText = new StringBuilder();
        if (StringUtils.isNotBlank(docName)) {
            fullText.append(docName).append("\n");
        }
        if (StringUtils.isNotBlank(contentText)) {
            fullText.append(contentText);
        }
        TextAnalysisResult analysisResult = hanLPTextAnalysisService.analyze(fullText.toString());
        try {
            luceneIndexManager.addDocument(
                    docId,
                    knowledgeId,
                    docName,
                    contentText,
                    analysisResult.getValidTerms(),
                    analysisResult.getKeywords()
            );
        } catch (Exception e) {
            log.error("文档索引操作失败: docId={}, error={}", docId, e.getMessage(), e);
            return false;
        }
        return true;
    }

    /**
     * 删除文档索引
     * @param docId 文档ID
     * @return 是否删除成功
     */
    public Boolean deleteLuceneIndex(String docId) {
        if (StringUtils.isBlank(docId)) {
            return false;
        }
        try {
            luceneIndexManager.deleteDocument(docId);
            return true;
        } catch (Exception e) {
            log.error("删除文档索引失败: docId={}, error={}", docId, e.getMessage());
            return false;
        }
    }

    /**
     * 根据知识库ID批量删除索引
     * @param knowledgeId 知识库ID
     * @return 删除的文档数量
     */
    public long deleteByKnowledgeId(String knowledgeId) {
        if (StringUtils.isBlank(knowledgeId)) {
            return 0;
        }
        try {
            return luceneIndexManager.deleteByKnowledgeId(knowledgeId);
        } catch (Exception e) {
            log.error("批量删除知识库索引失败: knowledgeId={}, error={}", knowledgeId, e.getMessage());
            return 0;
        }
    }

    /**
     * 根据知识文档标识集合批量删除文档索引
     *
     * @param docIds 知识文档标识集合
     * @return 删除的文档数量
     */
    public long deleteByKnowledgeId(List<Long> docIds) {
        return luceneIndexManager.deleteByDocIds(docIds);
    }

    /**
     * 关键词搜索文件
     * @param query 搜索内容
     * @param topK 返回条数
     * @param highlight 是否高亮
     * @param knowledgeIds 知识库ID集合，用于过滤搜索范围，null或空集合表示不过滤
     */
    public List<TextSearchVO> search(String query, Integer topK, Boolean highlight, Collection<String> knowledgeIds) {
        // 使用Lucene索引进行快速检索
        List<SearchResult> luceneResults = luceneIndexManager.search(
                query,
                topK * 2, // 多取一些，后续过滤
                highlight,
                "<em>",
                "</em>",
                knowledgeIds
        );

        // 合并Lucene评分和文档信息
        double maxScore = luceneResults.stream()
                .mapToDouble(r -> r.getScore().doubleValue())
                .max()
                .orElse(1.0);

        List<TextSearchVO> textSearchVOS = new ArrayList<>();
        for (SearchResult luceneResult : luceneResults) {
            // 归一化分数
            double normalizedScore = maxScore > 0
                    ? luceneResult.getScore().doubleValue() / maxScore
                    : 0.0;
            // 高亮显示
            String highlightedContent = luceneResult.getHighlight();
            TextSearchVO textSearch = TextSearchVO.builder()
                    .docId(luceneResult.getDocId())
                    .knowledgeId(luceneResult.getKnowledgeId())
                    .score(normalizedScore)
                    .highlightedContent(highlightedContent)
                    .build();
            textSearchVOS.add(textSearch);

            if (textSearchVOS.size() >= topK) {
                break;
            }
        }
        return textSearchVOS;
    }
}
