package com.cy.textsearch.index.support;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.cy.textsearch.index.LuceneConstants.*;

/**
 * Lucene查询构建器
 * <p>
 * 负责构建各种类型的Lucene查询对象，包括：
 * - 词项分词和过滤
 * - Token级别的召回查询
 * - 短语加分查询
 * - 严格短语查询
 * - minimumShouldMatch计算
 * </p>
 *
 * @author pcy
 * @date 2025年11月18日
 */
public class LuceneQueryBuilder {

    private static final Logger log = LoggerFactory.getLogger(LuceneQueryBuilder.class);

    /**
     * 文本分析器
     */
    private final Analyzer analyzer;

    /**
     * 构造函数
     *
     */
    public LuceneQueryBuilder(HanLPAnalyzer hanLPAnalyzer) {
        this.analyzer = hanLPAnalyzer;
    }

    /**
     * 从查询文本中提取词项
     * <p>
     * 使用两级降级策略保证分词的可靠性：
     * 1. 优先使用HanLPAnalyzer分词（包含自定义词典），保证与索引阶段一致
     * 2. 如果失败则降级使用空白分割作为兜底
     * </p>
     *
     * @param queryText 查询文本
     * @return 提取的词项列表
     */
    public List<String> extractQueryTerms(String queryText) {
        if (StringUtils.isBlank(queryText)) {
            return Collections.emptyList();
        }

        List<String> terms = new ArrayList<>();

        // 🔍 搜索分词效果日志
        log.info("🔍 搜索分词开始 [查询={}]", queryText);

        // 第一级：使用HanLPAnalyzer分词（包含自定义词典和过滤逻辑）
        terms = tryAnalyzerTokenization(queryText);
        if (!terms.isEmpty()) {
            log.debug("使用HanLPAnalyzer分词成功，提取{}个词项", terms.size());
            log.info("  - 分词策略: HanLPAnalyzer (自定义词典)");
            log.info("  - 分词结果: {} 个词项", terms.size());
            log.info("  - 词项列表: {}", terms);
            return terms;
        }

        // 第二级：降级使用空白分割（兜底策略）
        terms = tryWhitespaceTokenization(queryText);
        log.debug("使用空白分割，提取{}个词项", terms.size());
        log.info("  - 分词策略: 空白分割 (兜底)");
        log.info("  - 分词结果: {} 个词项", terms.size());
        log.info("  - 词项列表: {}", terms);
        return terms;
    }

    /**
     * 尝试使用Lucene Analyzer进行分词
     *
     * @param queryText 查询文本
     * @return 分词结果，失败返回空列表
     */
    private List<String> tryAnalyzerTokenization(String queryText) {
        List<String> terms = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream(FIELD_CONTENT, queryText)) {
            CharTermAttribute termAttr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            
            while (tokenStream.incrementToken()) {
                String word = termAttr.toString();
                // 跳过空白词项
                if (StringUtils.isBlank(word)) {
                    continue;
                }
                // 跳过纯标点符号和空白字符
                if (word.matches(REGEX_WHITESPACE_PUNCT)) {
                    continue;
                }
                terms.add(word);
            }
            tokenStream.end();
        } catch (Exception e) {
            // 分词失败，返回空列表触发降级策略
            log.debug("Analyzer分词失败: {}", e.getMessage());
            return Collections.emptyList();
        }
        return terms;
    }


    /**
     * 尝试使用空白分割进行分词
     *
     * @param queryText 查询文本
     * @return 分词结果
     */
    private List<String> tryWhitespaceTokenization(String queryText) {
        List<String> terms = new ArrayList<>();
        // 按空白字符分割
        String[] parts = queryText.trim().split(REGEX_WHITESPACE_SPLIT);
        for (String part : parts) {
            if (StringUtils.isBlank(part)) {
                continue;
            }
            terms.add(part);
        }
        return terms;
    }

    /**
     * 构建召回查询：按token维度组合查询
     * <p>
     * 查询结构设计：
     * - 外层SHOULD：每个token是一个SHOULD子句
     * - 子句内部使用DisjunctionMaxQuery在多个字段中匹配同一个token
     * - 通过minimumShouldMatch控制需要命中的token数，保证召回质量
     * </p>
     *
     * @param queryTerms 查询词项列表
     * @return 构建的召回查询对象
     */
    public Query buildTokenQuery(List<String> queryTerms) {
        // 过滤出适合召回的词项
        List<String> filteredTerms = filterQueryTerms(queryTerms);
        if (filteredTerms.isEmpty()) {
            // 没有有效词项，返回匹配空结果的查询
            return new MatchNoDocsQuery();
        }

        // 计算最小应匹配数，控制召回质量
        int minShouldMatch = calculateMinimumShouldMatch(filteredTerms.size());
        
        // 构建布尔查询，每个词项作为一个SHOULD子句
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.setMinimumNumberShouldMatch(minShouldMatch);

        // 为每个词项构建多字段查询
        for (String term : filteredTerms) {
            Query termQuery = buildTermDisMaxQuery(term);
            builder.add(termQuery, BooleanClause.Occur.SHOULD);
        }

        return builder.build();
    }

    /**
     * 过滤查询词项（通用过滤方法）
     * <p>
     * 过滤策略：
     * - 移除空白字符串
     * - 移除纯标点符号
     * - 移除高频停用词（提高查询质量）
     * </p>
     *
     * @param queryTerms 原始查询词项列表
     * @return 过滤后的词项列表
     */
    private List<String> filterQueryTerms(List<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> filtered = new ArrayList<>();
        for (String term : queryTerms) {
            // 跳过空白词项
            if (StringUtils.isBlank(term)) {
                continue;
            }
            // 跳过纯标点符号
            if (term.matches(REGEX_WHITESPACE_PUNCT)) {
                continue;
            }
            // 跳过高频停用词，提高查询质量
            if (PHRASE_STOP_WORDS.contains(term)) {
                continue;
            }
            filtered.add(term);
        }
        return filtered;
    }

    /**
     * 获取最小应匹配词项数
     * <p>
     * 标准全文检索策略：只要命中任意一个关键词就返回结果，
     * 由Lucene的TF-IDF/BM25评分机制自动对结果排序，
     * 匹配更多关键词的文档评分更高，排在前面。
     * </p>
     *
     * @param termCount 词项总数（未使用，保留参数以保持接口兼容）
     * @return 最小应匹配数，固定返回1
     */
    private int calculateMinimumShouldMatch(int termCount) {
        return MIN_SHOULD_MATCH_VALUE;
    }

    /**
     * 构建词项的DisjunctionMaxQuery
     * <p>
     * 在多个字段中查找同一个词项，取最高分数。
     * 字段权重设计：
     * - 标题字段：权重最高（3.0），标题匹配最重要
     * - 关键词字段：权重较高（2.0），关键词匹配重要
     * - 内容字段：权重中等（2.0），内容匹配是基础
     * - 有效词项字段：权重较低（1.5），辅助匹配
     * </p>
     *
     * @param term 词项
     * @return DisjunctionMaxQuery对象
     */
    private Query buildTermDisMaxQuery(String term) {
        List<Query> disjuncts = new ArrayList<>();
        
        // 在标题字段中查找，权重最高
        disjuncts.add(new BoostQuery(new TermQuery(new Term(FIELD_TITLE, term)), BOOST_TITLE));
        
        // 在内容字段中查找，基础权重
        disjuncts.add(new BoostQuery(new TermQuery(new Term(FIELD_CONTENT, term)), BOOST_CONTENT));
        
        // 在有效词项字段中查找，辅助权重
        disjuncts.add(new BoostQuery(new TermQuery(new Term(FIELD_VALID_TERMS, term)), BOOST_VALID_TERMS));
        
        // 在关键词字段中查找，重要权重
        disjuncts.add(new BoostQuery(new TermQuery(new Term(FIELD_KEYWORDS, term)), BOOST_KEYWORDS));
        
        // 使用TieBreaker避免只考虑最高分字段
        return new DisjunctionMaxQuery(disjuncts, TIE_BREAKER);
    }

    /**
     * 构建短语加分查询（SHOULD）
     * <p>
     * 用于排序加分，不影响召回范围。
     * 当查询词项能组成短语时，给予额外的相关性加分。
     * </p>
     *
     * @param field 字段名
     * @param queryTerms 查询词项列表
     * @param slop 允许的词间距离
     * @param boost 权重加分
     * @return 短语查询对象，如果词项少于2个则返回null
     */
    public Query buildPhraseBoostQuery(String field, List<String> queryTerms, int slop, float boost) {
        // 过滤出适合短语匹配的词项
        List<String> phraseTerms = filterQueryTerms(queryTerms);
        if (phraseTerms.size() < MIN_PHRASE_TERMS) {
            // 词项数不足，无法构建短语查询
            return null;
        }
        
        // 构建短语查询
        PhraseQuery.Builder phraseBuilder = new PhraseQuery.Builder();
        phraseBuilder.setSlop(Math.max(0, slop)); // 确保slop不为负
        
        // 添加所有短语词项
        for (String term : phraseTerms) {
            phraseBuilder.add(new Term(field, term));
        }
        
        // 应用权重加分
        return new BoostQuery(phraseBuilder.build(), boost);
    }


    /**
     * 从文本构建严格短语查询（MUST）
     * <p>
     * 用于长查询的严格匹配模式。
     * 使用QueryParser解析完整查询文本为短语查询，要求文档包含完整短语。
     * </p>
     *
     * @param field 字段名
     * @param queryText 查询文本
     * @param slop 允许的词间距离
     * @return 短语查询对象，解析失败返回null
     */
    public Query buildPhraseMustQueryFromText(String field, String queryText, int slop) {
        if (StringUtils.isBlank(field) || StringUtils.isBlank(queryText)) {
            return null;
        }
        
        try {
            // 使用QueryParser解析带引号的短语查询
            QueryParser parser = new QueryParser(field, analyzer);
            parser.setPhraseSlop(Math.max(0, slop)); // 设置短语slop
            
            // 转义查询文本并加上引号，构成短语查询
            String phraseQuery = "\"" + QueryParser.escape(queryText) + "\"";
            return parser.parse(phraseQuery);
            
        } catch (ParseException e) {
            log.debug("短语查询解析失败: {}", e.getMessage());
            return null;
        }
    }
}
