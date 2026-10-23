package com.cy.textsearch.index.support;

import com.cy.textsearch.entity.SearchResult;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.cy.textsearch.index.LuceneConstants.*;

/**
 * Lucene搜索执行器
 * <p>
 * 负责执行具体的搜索操作，包括：
 * - 构建复合查询策略（召回+加分+严格短语）
 * - 执行搜索并处理结果
 * - 高亮显示处理
 * - 搜索结果封装
 * </p>
 *
 * @author pcy
 * @date 2025年11月18日
 */
public class LuceneSearcher {

    private static final Logger log = LoggerFactory.getLogger(LuceneSearcher.class);

    /**
     * 查询构建器
     */
    private final LuceneQueryBuilder queryBuilder;

    /**
     * 高亮处理器
     */
    private final LuceneHighlighter highlighter;

    /**
     * 构造函数
     *
     * @param queryBuilder 查询构建器
     * @param highlighter 高亮处理器
     */
    public LuceneSearcher(LuceneQueryBuilder queryBuilder, LuceneHighlighter highlighter) {
        this.queryBuilder = queryBuilder;
        this.highlighter = highlighter;
    }

    /**
     * 执行全文搜索
     * <p>
     * 搜索策略说明：
     * 1. 构建基础召回查询（Token级别的SHOULD查询）
     * 2. 添加短语加分查询（提升完整短语匹配的排序）
     * 3. 对长查询启用严格短语过滤（先尝试严格匹配，失败后回退）
     * 4. 执行搜索并处理结果
     * 5. 生成高亮显示
     * </p>
     *
     * @param indexDirectory 索引目录
     * @param queryText 查询文本
     * @param topK 返回的最大结果数
     * @param enableHighlight 是否启用高亮
     * @param highlightPreTag 高亮前缀标签
     * @param highlightPostTag 高亮后缀标签
     * @return 搜索结果列表
     */
    public List<SearchResult> search(Directory indexDirectory,
                                     String queryText,
                                     int topK,
                                     boolean enableHighlight,
                                     String highlightPreTag,
                                     String highlightPostTag,
                                     Collection<String> knowledgeIds) {
        List<SearchResult> results = new ArrayList<>();

        try (IndexReader reader = DirectoryReader.open(indexDirectory)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            // 第一步：从查询文本中提取词项，统一分词策略
            List<String> queryTerms = queryBuilder.extractQueryTerms(queryText);
            if (queryTerms.isEmpty()) {
                log.debug("查询文本分词结果为空: {}", queryText);
                return results;
            }

            // 第二步：分别过滤出适合不同用途的词项
            List<String> matchTerms = filterTermsForMatch(queryTerms);
            List<String> phraseTerms = filterTermsForPhrase(queryTerms);
            log.debug("词项分析结果 - 原始: {}, 召回: {}, 短语: {}", queryTerms.size(), matchTerms.size(), phraseTerms.size());

            // 第三步：构建基础召回查询
            Query baseQuery = queryBuilder.buildTokenQuery(queryTerms);
            if (baseQuery instanceof MatchNoDocsQuery) {
                log.debug("无有效召回词项，返回空结果");
                return results;
            }

            // 第四步：构建短语加分查询（SHOULD，仅用于提升排序）
            Query contentPhraseBoost = queryBuilder.buildPhraseBoostQuery(
                    FIELD_CONTENT, queryTerms, PHRASE_SLOP_CONTENT, BOOST_CONTENT_PHRASE);
            Query titlePhraseBoost = queryBuilder.buildPhraseBoostQuery(
                    FIELD_TITLE, queryTerms, PHRASE_SLOP_TITLE, BOOST_TITLE_PHRASE);

            // 第五步：组合最终查询（召回 + 短语加分）
            BooleanQuery.Builder finalQueryBuilder = new BooleanQuery.Builder();
            finalQueryBuilder.add(baseQuery, BooleanClause.Occur.MUST); // 基础召回是必须的

            // 添加短语加分（可选，仅影响排序）
            if (contentPhraseBoost != null) {
                finalQueryBuilder.add(contentPhraseBoost, BooleanClause.Occur.SHOULD);
                log.debug("添加内容短语加分查询");
            }
            if (titlePhraseBoost != null) {
                finalQueryBuilder.add(titlePhraseBoost, BooleanClause.Occur.SHOULD);
                log.debug("添加标题短语加分查询");
            }

            Query finalQuery = finalQueryBuilder.build();

            // 第六步：添加知识库ID过滤条件
            if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
                BooleanQuery.Builder filterBuilder = new BooleanQuery.Builder();
                filterBuilder.add(finalQuery, BooleanClause.Occur.MUST);
                
                // 构建知识库ID过滤查询（OR关系）
                BooleanQuery.Builder knowledgeIdFilter = new BooleanQuery.Builder();
                for (String knowledgeId : knowledgeIds) {
                    knowledgeIdFilter.add(
                        new TermQuery(new Term(FIELD_KNOWLEDGE_ID, knowledgeId)),
                        BooleanClause.Occur.SHOULD
                    );
                }
                filterBuilder.add(knowledgeIdFilter.build(), BooleanClause.Occur.MUST);
                finalQuery = filterBuilder.build();
                log.debug("添加知识库ID过滤: {}", knowledgeIds);
            }

            // 第七步：对长查询尝试严格短语过滤
            Query usedQuery = finalQuery;
            TopDocs topDocs = tryStrictPhraseSearch(searcher, finalQuery, queryText, phraseTerms, topK);
            if (topDocs == null) {
                // 严格短语搜索失败或不适用，使用常规搜索
                topDocs = searcher.search(finalQuery, topK);
                log.info("使用常规搜索策略: {}", finalQuery);
            } else {
                // 严格短语搜索成功，更新使用的查询
                usedQuery = buildStrictPhraseQuery(finalQuery, queryText);
                log.info("使用严格短语搜索策略");
            }

            // 第八步：处理搜索结果
            results = processSearchResults(searcher, topDocs, usedQuery, queryText, 
                                         enableHighlight, highlightPreTag, highlightPostTag);

            log.info("搜索完成: query='{}', 结果数={}", queryText, results.size());

        } catch (IndexNotFoundException e) {
            log.warn("索引为空，还没有文档被索引: {}", e.getMessage());
            return results;
        } catch (IOException e) {
            log.error("执行搜索时发生IO异常: query='{}'", queryText, e);
        } catch (Exception e) {
            log.error("执行搜索时发生未知异常: query='{}'", queryText, e);
        }

        return results;
    }

    /**
     * 尝试严格短语搜索
     * <p>
     * 严格搜索的触发条件：
     * - 查询文本长度 >= 8个字符
     * - 短语词项数 >= 3个
     * 
     * 严格搜索策略：
     * - 在基础查询的基础上添加严格短语MUST条件
     * - 如果有结果则使用严格结果
     * - 如果无结果则回退到宽松搜索
     * </p>
     *
     * @param searcher 索引搜索器
     * @param baseQuery 基础查询
     * @param queryText 查询文本
     * @param phraseTerms 短语词项
     * @param topK 结果数量限制
     * @return 搜索结果，null表示不适用严格搜索或搜索失败
     */
    private TopDocs tryStrictPhraseSearch(IndexSearcher searcher, Query baseQuery, 
                                        String queryText, List<String> phraseTerms, int topK) {
        // 检查是否满足严格短语搜索的条件
        if (queryText == null || 
            queryText.length() < STRICT_PHRASE_MIN_QUERY_LENGTH || 
            phraseTerms.size() < STRICT_PHRASE_MIN_TERMS) {
            return null; // 不满足条件，不启用严格搜索
        }

        try {
            // 构建严格短语查询
            Query strictPhraseQuery = queryBuilder.buildPhraseMustQueryFromText(
                    FIELD_CONTENT, queryText, PHRASE_SLOP_STRICT);
            
            if (strictPhraseQuery == null) {
                return null; // 短语查询构建失败
            }

            // 组合严格查询：基础查询 AND 严格短语查询
            BooleanQuery.Builder strictBuilder = new BooleanQuery.Builder();
            strictBuilder.add(baseQuery, BooleanClause.Occur.MUST);
            strictBuilder.add(strictPhraseQuery, BooleanClause.Occur.MUST);
            
            Query strictQuery = strictBuilder.build();
            TopDocs strictResults = searcher.search(strictQuery, topK);
            
            // 检查严格搜索是否有结果
            if (strictResults.scoreDocs != null && strictResults.scoreDocs.length > 0) {
                log.debug("严格短语搜索命中{}个结果", strictResults.scoreDocs.length);
                return strictResults;
            } else {
                log.debug("严格短语搜索无结果，将回退到宽松搜索");
                return null;
            }
            
        } catch (Exception e) {
            log.debug("严格短语搜索执行异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建严格短语查询（用于日志记录）
     *
     * @param baseQuery 基础查询
     * @param queryText 查询文本
     * @return 严格短语查询
     */
    private Query buildStrictPhraseQuery(Query baseQuery, String queryText) {
        Query strictPhraseQuery = queryBuilder.buildPhraseMustQueryFromText(
                FIELD_CONTENT, queryText, PHRASE_SLOP_STRICT);
        
        if (strictPhraseQuery == null) {
            return baseQuery;
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(baseQuery, BooleanClause.Occur.MUST);
        builder.add(strictPhraseQuery, BooleanClause.Occur.MUST);
        return builder.build();
    }

    /**
     * 处理搜索结果
     * <p>
     * 结果处理流程：
     * 1. 遍历搜索命中的文档
     * 2. 提取文档ID和相关性评分
     * 3. 根据需要生成高亮显示
     * 4. 封装为SearchResult对象
     * </p>
     *
     * @param searcher 索引搜索器
     * @param topDocs 搜索结果
     * @param query 实际使用的查询
     * @param queryText 查询文本
     * @param enableHighlight 是否启用高亮
     * @param highlightPreTag 高亮前缀标签
     * @param highlightPostTag 高亮后缀标签
     * @return 处理后的搜索结果列表
     * @throws IOException IO异常
     */
    private List<SearchResult> processSearchResults(IndexSearcher searcher, TopDocs topDocs,
                                                  Query query, String queryText,
                                                  boolean enableHighlight,
                                                  String highlightPreTag, String highlightPostTag) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        
        if (topDocs == null || topDocs.scoreDocs == null) {
            return results;
        }

        // 遍历每个搜索命中
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            try {
                // 获取文档内容
                org.apache.lucene.document.Document doc = searcher.doc(scoreDoc.doc);
                
                // 提取文档ID
                String docIdStr = doc.get(FIELD_DOC_ID);
                if (docIdStr == null) {
                    log.warn("文档缺少docId字段，跳过: docIndex={}", scoreDoc.doc);
                    continue;
                }
                
                Long docId = Long.parseLong(docIdStr);
                String knowledgeId = doc.get(FIELD_KNOWLEDGE_ID);
                Float score = scoreDoc.score;
                
                // 生成高亮显示（如果需要）
                String highlight = null;
                if (enableHighlight) {
                    String content = doc.get(FIELD_CONTENT);
                    if (content != null) {
                        highlight = highlighter.buildHighlight(query, queryText, content, 
                                                             highlightPreTag, highlightPostTag);
                    }
                }
                
                // 封装结果
                results.add(new SearchResult(docId, knowledgeId, score, highlight));
                
            } catch (NumberFormatException e) {
                log.warn("文档ID格式错误: docIndex={}, docId={}", scoreDoc.doc, 
                        searcher.doc(scoreDoc.doc).get(FIELD_DOC_ID));
            } catch (Exception e) {
                log.warn("处理搜索结果时发生异常: docIndex={}", scoreDoc.doc, e);
            }
        }
        
        return results;
    }

    /**
     * 过滤用于匹配的词项（复制queryBuilder中的逻辑以保持一致性）
     */
    private List<String> filterTermsForMatch(List<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<String> filtered = new ArrayList<>();
        for (String term : queryTerms) {
            if (term == null || term.trim().isEmpty()) {
                continue;
            }
            if (term.matches(REGEX_WHITESPACE_PUNCT)) {
                continue;
            }
            if (PHRASE_STOP_WORDS.contains(term)) {
                continue;
            }
            filtered.add(term);
        }
        return filtered;
    }

    /**
     * 过滤用于短语查询的词项（复制queryBuilder中的逻辑以保持一致性）
     */
    private List<String> filterTermsForPhrase(List<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<String> phrase = new ArrayList<>();
        for (String term : queryTerms) {
            if (term == null || term.trim().isEmpty()) {
                continue;
            }
            if (term.matches(REGEX_WHITESPACE_PUNCT)) {
                continue;
            }
            if (PHRASE_STOP_WORDS.contains(term)) {
                continue;
            }
            phrase.add(term);
        }
        return phrase;
    }
}
