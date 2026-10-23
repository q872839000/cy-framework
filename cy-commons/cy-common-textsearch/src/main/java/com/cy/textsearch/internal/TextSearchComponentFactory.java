package com.cy.textsearch.internal;

import com.cy.textsearch.config.TextAnalysisProperties;
import com.cy.textsearch.core.ChineseTextProcessor;
import com.cy.textsearch.index.LuceneIndexManager;
import com.cy.textsearch.index.support.HanLPAnalyzer;
import com.cy.textsearch.index.support.LuceneHighlighter;
import com.cy.textsearch.index.support.LuceneQueryBuilder;
import com.cy.textsearch.index.support.LuceneSearcher;
import com.cy.textsearch.service.HanLPTextAnalysisService;

/**
 * 文本搜索内部组件工厂
 * <p>
 * 负责创建和管理所有内部组件的生命周期，对外不暴露。
 * 组件采用懒加载方式创建，保证单例。
 * </p>
 *
 * @author pcy
 */
public class TextSearchComponentFactory {

    private final TextAnalysisProperties properties;

    // 懒加载的组件实例
    private volatile ChineseTextProcessor textProcessor;
    private volatile HanLPAnalyzer analyzer;
    private volatile LuceneQueryBuilder queryBuilder;
    private volatile LuceneHighlighter highlighter;
    private volatile LuceneSearcher searcher;
    private volatile LuceneIndexManager indexManager;
    private volatile HanLPTextAnalysisService analysisService;

    public TextSearchComponentFactory(TextAnalysisProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取中文文本处理器
     */
    public synchronized ChineseTextProcessor getTextProcessor() {
        if (textProcessor == null) {
            textProcessor = new ChineseTextProcessor();
            if (properties != null) {
                textProcessor.setTextAnalysisProperties(properties);
            }
        }
        return textProcessor;
    }

    /**
     * 获取HanLP分析器
     */
    public synchronized HanLPAnalyzer getAnalyzer() {
        if (analyzer == null) {
            analyzer = new HanLPAnalyzer(getTextProcessor());
        }
        return analyzer;
    }

    /**
     * 获取Lucene查询构建器
     */
    public synchronized LuceneQueryBuilder getQueryBuilder() {
        if (queryBuilder == null) {
            queryBuilder = new LuceneQueryBuilder(getAnalyzer());
        }
        return queryBuilder;
    }

    /**
     * 获取Lucene高亮处理器
     */
    public synchronized LuceneHighlighter getHighlighter() {
        if (highlighter == null) {
            highlighter = new LuceneHighlighter(getQueryBuilder(), getAnalyzer());
        }
        return highlighter;
    }

    /**
     * 获取Lucene搜索器
     */
    public synchronized LuceneSearcher getSearcher() {
        if (searcher == null) {
            searcher = new LuceneSearcher(getQueryBuilder(), getHighlighter());
        }
        return searcher;
    }

    /**
     * 获取Lucene索引管理器
     */
    public synchronized LuceneIndexManager getIndexManager() {
        if (indexManager == null) {
            String indexPath = (properties != null && properties.getIndexPath() != null)
                    ? properties.getIndexPath()
                    : "Lucene-index";
            indexManager = new LuceneIndexManager(indexPath, getSearcher(), getAnalyzer());
        }
        return indexManager;
    }

    /**
     * 获取HanLP文本分析服务
     */
    public synchronized HanLPTextAnalysisService getAnalysisService() {
        if (analysisService == null) {
            analysisService = new HanLPTextAnalysisService(getTextProcessor());
        }
        return analysisService;
    }
}
