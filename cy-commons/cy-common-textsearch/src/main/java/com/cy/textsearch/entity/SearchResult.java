package com.cy.textsearch.entity;

import lombok.Getter;

/**
 * 搜索结果数据类
 */
@Getter
public class SearchResult {

    /**
     * 文档ID
     */
    private final Long docId;

    /**
     * 知识库ID
     */
    private final String knowledgeId;

    /**
     * 相关性评分
     */
    private final Float score;

    /**
     * 高亮片段
     */
    private final String highlight;

    /**
     * 构造函数
     *
     * @param docId 文档ID
     * @param knowledgeId 知识库ID
     * @param score 相关性评分
     * @param highlight 高亮片段
     */
    public SearchResult(Long docId, String knowledgeId, Float score, String highlight) {
        this.docId = docId;
        this.knowledgeId = knowledgeId;
        this.score = score;
        this.highlight = highlight;
    }

    @Override
    public String toString() {
        return String.format("SearchResult{docId=%d, knowledgeId=%s, score=%.4f, hasHighlight=%s}",
                docId, knowledgeId, score, highlight != null);
    }
}
