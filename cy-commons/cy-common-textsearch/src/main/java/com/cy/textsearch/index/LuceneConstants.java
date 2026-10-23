package com.cy.textsearch.index;

import java.util.Set;

/**
 * Lucene搜索引擎相关常量定义
 * <p>
 * 集中管理所有Lucene相关的常量配置，供多个类共享使用。
 * 包括字段名称、权重配置、查询参数、高亮配置、正则表达式等。
 * </p>
 *
 * @author pcy
 * @date 2025年11月18日
 */
public final class LuceneConstants {

    // ==================== 字段名称常量 ====================
    
    /**
     * 文档ID字段名
     */
    public static final String FIELD_DOC_ID = "docId";

    /**
     * 知识库ID字段名
     */
    public static final String FIELD_KNOWLEDGE_ID = "knowledgeId";
    
    /**
     * 标题字段名
     */
    public static final String FIELD_TITLE = "title";
    
    /**
     * 内容字段名
     */
    public static final String FIELD_CONTENT = "content";
    
    /**
     * 有效词项字段名
     */
    public static final String FIELD_VALID_TERMS = "validTerms";
    
    /**
     * 关键词字段名
     */
    public static final String FIELD_KEYWORDS = "keywords";

    // ==================== 权重和评分常量 ====================
    
    /**
     * 标题字段权重
     */
    public static final float BOOST_TITLE = 3.0f;
    
    /**
     * 内容字段权重
     */
    public static final float BOOST_CONTENT = 2.0f;
    
    /**
     * 有效词项字段权重
     */
    public static final float BOOST_VALID_TERMS = 1.5f;
    
    /**
     * 关键词字段权重
     */
    public static final float BOOST_KEYWORDS = 2.0f;
    
    /**
     * 内容短语匹配权重
     */
    public static final float BOOST_CONTENT_PHRASE = 20.0f;
    
    /**
     * 标题短语匹配权重
     */
    public static final float BOOST_TITLE_PHRASE = 25.0f;
    
    /**
     * DisjunctionMaxQuery的tieBreaker值
     */
    public static final float TIE_BREAKER = 0.1f;
    
    /**
     * 关键词权重转换为重复次数的乘数
     */
    public static final int KEYWORD_WEIGHT_MULTIPLIER = 5;

    // ==================== 短语查询配置常量 ====================
    
    /**
     * 内容短语查询的slop值（允许的词间距离）
     */
    public static final int PHRASE_SLOP_CONTENT = 2;
    
    /**
     * 标题短语查询的slop值（要求精确匹配）
     */
    public static final int PHRASE_SLOP_TITLE = 0;
    
    /**
     * 严格短语查询的slop值
     */
    public static final int PHRASE_SLOP_STRICT = 4;

    // ==================== 查询条件阈值常量 ====================
    
    /**
     * 触发严格短语查询的最小查询文本长度
     */
    public static final int STRICT_PHRASE_MIN_QUERY_LENGTH = 8;
    
    /**
     * 触发严格短语查询的最小短语词项数
     */
    public static final int STRICT_PHRASE_MIN_TERMS = 3;
    
    /**
     * minimumShouldMatch的值
     * <p>
     * 设置为1表示只要命中任意一个关键词就返回结果，
     * 由Lucene的TF-IDF/BM25评分机制自动排序，
     * 匹配更多关键词的文档会排在前面。
     * </p>
     */
    public static final int MIN_SHOULD_MATCH_VALUE = 1;

    // ==================== 高亮显示配置常量 ====================
    
    /**
     * 默认高亮前缀标签
     */
    public static final String DEFAULT_HIGHLIGHT_PRE_TAG = "<em>";
    
    /**
     * 默认高亮后缀标签
     */
    public static final String DEFAULT_HIGHLIGHT_POST_TAG = "</em>";
    
    /**
     * 高亮片段的最大长度
     */
    public static final int HIGHLIGHT_FRAGMENT_SIZE = 150;
    
    /**
     * 高亮上下文窗口大小（短语匹配）
     */
    public static final int HIGHLIGHT_CONTEXT_SIZE = 120;
    
    /**
     * 高亮窗口搜索的上下文大小
     */
    public static final int HIGHLIGHT_WINDOW_CONTEXT = 150;
    
    /**
     * 默认摘要长度
     */
    public static final int DEFAULT_SUMMARY_LENGTH = 200;

    // ==================== 正则表达式常量 ====================
    
    /**
     * 匹配纯空白和标点符号的正则表达式
     */
    public static final String REGEX_WHITESPACE_PUNCT = "^[\\s\\p{Punct}]+$";
    
    /**
     * 匹配ASCII字母数字和常见符号的正则表达式
     */
    public static final String REGEX_ASCII_WORD = "^[A-Za-z0-9._-]+$";
    
    /**
     * 空白分割正则表达式
     */
    public static final String REGEX_WHITESPACE_SPLIT = "\\s+";

    // ==================== 其他配置常量 ====================
    
    /**
     * 短语查询的最小词项数
     */
    public static final int MIN_PHRASE_TERMS = 2;
    
    /**
     * 高亮过滤的最小单字符长度（仅保留ASCII单字符）
     */
    public static final int MIN_SINGLE_CHAR_LENGTH = 1;

    // ==================== 停用词常量 ====================
    
    /**
     * 短语查询中需要过滤的停用词集合
     * <p>
     * 这些高频词汇在短语匹配时会被过滤，以提高搜索精确度。
     * 包含常见的介词、连词、助词等。
     * </p>
     */
    public static final Set<String> PHRASE_STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这",
            "那", "他", "她", "它", "们", "些", "这样", "那样", "什么", "怎么", "为什么", "因为", "所以", "但是", "然而", "虽然", "尽管", "如果", "假如", "即使", "无论",
            "与", "及", "或", "而", "但", "并", "且", "以及", "或者", "还是"
    );

    /**
     * 私有构造函数，防止实例化常量类
     */
    private LuceneConstants() {
        throw new UnsupportedOperationException("常量类不允许实例化");
    }
}
