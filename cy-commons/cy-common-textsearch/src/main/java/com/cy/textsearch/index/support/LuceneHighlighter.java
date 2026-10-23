package com.cy.textsearch.index.support;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cy.textsearch.index.LuceneConstants.*;

/**
 * Lucene高亮显示处理器
 * <p>
 * 负责为搜索结果生成高亮显示的文本片段，包括：
 * - 短语匹配高亮
 * - 标准Lucene高亮
 * - 忽略空白和标点的模糊匹配高亮
 * - 多词项窗口高亮
 * - 智能摘要生成
 * </p>
 *
 * @author pcy
 * @date 2025年11月18日
 */
public class LuceneHighlighter {

    private static final Logger log = LoggerFactory.getLogger(LuceneHighlighter.class);

    /**
     * 文本分析器
     */
    private final Analyzer analyzer;

    /**
     * 查询构建器，用于提取查询词项
     */
    private final LuceneQueryBuilder queryBuilder;

    /**
     * 构造函数
     *
     * @param queryBuilder 查询构建器
     */
    public LuceneHighlighter(LuceneQueryBuilder queryBuilder, HanLPAnalyzer hanLPAnalyzer) {
        this.analyzer = hanLPAnalyzer;
        this.queryBuilder = queryBuilder;
    }

    /**
     * 构建高亮片段
     * <p>
     * 高亮策略的优先级：
     * 1. 短语匹配高亮（精确或近似匹配）
     * 2. 标准Lucene高亮（基于查询对象）
     * 3. 默认摘要（截取前N个字符）
     * </p>
     *
     * @param query 查询对象
     * @param queryText 查询文本
     * @param content 文档内容
     * @param highlightPreTag 高亮前缀标签，null时使用默认值
     * @param highlightPostTag 高亮后缀标签，null时使用默认值
     * @return 高亮片段，如果无法生成则返回null或摘要
     */
    public String buildHighlight(Query query, String queryText, String content, 
                                String highlightPreTag, String highlightPostTag) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        // 规范化高亮标签，使用默认值填补null
        String pre = highlightPreTag != null ? highlightPreTag : DEFAULT_HIGHLIGHT_PRE_TAG;
        String post = highlightPostTag != null ? highlightPostTag : DEFAULT_HIGHLIGHT_POST_TAG;

        // 优先策略：尝试短语匹配高亮
        String phraseFragment = buildPhraseFragment(content, queryText, pre, post);
        if (StringUtils.isNotBlank(phraseFragment)) {
            log.debug("使用短语匹配高亮成功");
            return phraseFragment;
        }

        // 次优策略：使用标准Lucene高亮器
        String luceneFragment = buildLuceneHighlight(query, content, pre, post);
        if (StringUtils.isNotBlank(luceneFragment) && luceneFragment.contains(pre)) {
            log.debug("使用Lucene标准高亮成功");
            return luceneFragment;
        }

        // 兜底策略：生成默认摘要
        log.debug("高亮失败，返回默认摘要");
        return buildDefaultSummary(content);
    }

    /**
     * 构建短语匹配的高亮片段
     * <p>
     * 匹配策略的优先级：
     * 1. 精确字符串匹配
     * 2. 忽略空白和标点的模糊匹配
     * 3. 基于词项的窗口匹配
     * </p>
     *
     * @param content 文档内容
     * @param queryText 查询文本
     * @param pre 高亮前缀标签
     * @param post 高亮后缀标签
     * @return 高亮片段，如果无法找到匹配则返回null
     */
    private String buildPhraseFragment(String content, String queryText, String pre, String post) {
        if (StringUtils.isBlank(content) || StringUtils.isBlank(queryText)) {
            return null;
        }

        // 策略1：尝试精确字符串匹配
        int startIndex = content.indexOf(queryText);
        int endIndex = startIndex >= 0 ? startIndex + queryText.length() : -1;

        // 策略2：如果精确匹配失败，尝试忽略空白和标点的模糊匹配
        if (startIndex < 0) {
            int[] match = findIgnoreWhitespace(content, queryText);
            if (match != null) {
                startIndex = match[0];
                endIndex = match[1];
                log.debug("使用忽略空白匹配找到位置: [{}, {}]", startIndex, endIndex);
            }
        }

        // 策略3：如果仍然匹配失败，使用词项窗口匹配
        if (startIndex < 0 || endIndex <= startIndex) {
            return buildWindowHighlight(content, queryText, pre, post);
        }

        // 提取查询词项，用于高亮片段中所有匹配的词
        List<String> queryTerms = queryBuilder.extractQueryTerms(queryText);
        List<String> highlightTerms = filterTermsForHighlight(queryTerms);

        // 在匹配位置周围构建高亮片段
        return buildContextFragment(content, startIndex, endIndex, highlightTerms, pre, post);
    }

    /**
     * 在内容中查找忽略空白和标点的匹配
     * <p>
     * 算法说明：
     * - 在content中逐字符扫描，寻找与queryText匹配的子串
     * - 匹配时忽略空白字符和标点符号
     * - 对ASCII字母进行大小写不敏感匹配
     * </p>
     *
     * @param content 文档内容
     * @param queryText 查询文本
     * @return 匹配的起始和结束位置数组[start, end]，未找到返回null
     */
    private int[] findIgnoreWhitespace(String content, String queryText) {
        int contentLength = content.length();
        int queryLength = queryText.length();
        
        if (queryLength <= 0 || contentLength <= 0) {
            return null;
        }

        // 外层循环：在content中寻找起始匹配点
        for (int i = 0; i < contentLength; i++) {
            int contentIndex = i;  // content中的当前位置
            int queryIndex = 0;    // queryText中的当前位置

            // 跳过内容起始位置的无关字符
            while (contentIndex < contentLength && isIgnorableChar(content.charAt(contentIndex))) {
                contentIndex++;
            }
            int matchStart = contentIndex; // 记录实际匹配开始位置

            // 内层循环：逐字符匹配
            while (contentIndex < contentLength && queryIndex < queryLength) {
                char contentChar = content.charAt(contentIndex);
                char queryChar = queryText.charAt(queryIndex);

                // 跳过content中的无关字符
                if (isIgnorableChar(contentChar)) {
                    contentIndex++;
                    continue;
                }

                // 跳过query中的无关字符
                if (isIgnorableChar(queryChar)) {
                    queryIndex++;
                    continue;
                }

                // 字符匹配比较（支持ASCII大小写不敏感）
                if (equalsIgnoreCaseAscii(contentChar, queryChar)) {
                    contentIndex++;
                    queryIndex++;
                } else {
                    // 匹配失败，跳出内层循环
                    break;
                }
            }

            // 检查是否完全匹配
            if (queryIndex == queryLength) {
                return new int[]{matchStart, contentIndex};
            }
        }
        
        return null; // 未找到匹配
    }

    /**
     * 判断字符是否可忽略（空白或标点）
     * <p>
     * 可忽略的字符类型：
     * - 所有空白字符
     * - 连接标点（如下划线）
     * - 破折号标点
     * - 起始/结束标点（括号等）
     * - 引号标点
     * - 其他标点符号
     * </p>
     *
     * @param c 字符
     * @return 如果是空白或标点符号返回true
     */
    private boolean isIgnorableChar(char c) {
        // 空白字符直接忽略
        if (Character.isWhitespace(c)) {
            return true;
        }
        
        // 获取Unicode字符类型
        int charType = Character.getType(c);
        
        // 忽略各种类型的标点符号
        return charType == Character.CONNECTOR_PUNCTUATION     // 连接标点：_
            || charType == Character.DASH_PUNCTUATION          // 破折号：-
            || charType == Character.START_PUNCTUATION         // 起始标点：(
            || charType == Character.END_PUNCTUATION           // 结束标点：)
            || charType == Character.INITIAL_QUOTE_PUNCTUATION // 起始引号
            || charType == Character.FINAL_QUOTE_PUNCTUATION   // 结束引号
            || charType == Character.OTHER_PUNCTUATION;        // 其他标点：,.!?等
    }

    /**
     * 比较两个字符是否相等（忽略ASCII字母大小写）
     * <p>
     * 比较规则：
     * - 字符完全相同：直接返回true
     * - 都是ASCII字母：忽略大小写比较
     * - 其他情况：返回false
     * </p>
     *
     * @param a 字符a
     * @param b 字符b
     * @return 如果相等返回true
     */
    private boolean equalsIgnoreCaseAscii(char a, char b) {
        if (a == b) {
            return true; // 完全相同
        }
        
        // 仅对ASCII字母进行大小写不敏感比较
        if (isAsciiLetter(a) && isAsciiLetter(b)) {
            return Character.toLowerCase(a) == Character.toLowerCase(b);
        }
        
        return false;
    }

    /**
     * 判断字符是否为ASCII字母
     *
     * @param c 字符
     * @return 如果是ASCII字母返回true
     */
    private boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * 构建基于词项窗口的高亮片段
     * <p>
     * 当无法进行短语匹配时的降级策略：
     * 1. 从查询文本中提取词项
     * 2. 过滤出适合高亮的词项
     * 3. 找到包含最多词项的最佳窗口
     * 4. 在窗口中高亮所有匹配的词项
     * </p>
     *
     * @param content 文档内容
     * @param queryText 查询文本
     * @param pre 高亮前缀标签
     * @param post 高亮后缀标签
     * @return 高亮片段，找不到返回null
     */
    private String buildWindowHighlight(String content, String queryText, String pre, String post) {
        // 从查询文本中提取词项
        List<String> queryTerms = queryBuilder.extractQueryTerms(queryText);
        List<String> highlightTerms = filterTermsForHighlight(queryTerms);
        
        if (highlightTerms.isEmpty()) {
            return null;
        }

        // 寻找最佳高亮窗口
        int[] window = findBestWindowByAnchor(content, highlightTerms);
        if (window == null) {
            return null;
        }

        // 提取窗口片段
        int from = window[0];
        int to = window[1];
        String fragment = content.substring(from, to);
        
        // 在片段中高亮词项
        fragment = highlightTerms(fragment, highlightTerms, pre, post);
        
        // 检查高亮是否成功
        if (StringUtils.isBlank(fragment) || !fragment.contains(pre)) {
            return null;
        }

        // 添加省略号提示
        if (from > 0) {
            fragment = "..." + fragment;
        }
        if (to < content.length()) {
            fragment = fragment + "...";
        }
        
        return fragment;
    }

    /**
     * 在匹配位置周围构建带上下文的高亮片段
     * <p>
     * 不仅高亮主匹配位置，还会高亮片段中所有出现的查询词项。
     * </p>
     *
     * @param content 文档内容
     * @param startIndex 匹配起始位置
     * @param endIndex 匹配结束位置
     * @param highlightTerms 需要高亮的词项列表
     * @param pre 高亮前缀标签
     * @param post 高亮后缀标签
     * @return 带高亮的上下文片段
     */
    private String buildContextFragment(String content, int startIndex, int endIndex, 
                                        List<String> highlightTerms, String pre, String post) {
        // 计算上下文边界，确保不越界
        int contextFrom = Math.max(0, startIndex - HIGHLIGHT_CONTEXT_SIZE);
        int contextTo = Math.min(content.length(), endIndex + HIGHLIGHT_CONTEXT_SIZE);

        // 提取片段内容
        String fragment = content.substring(contextFrom, contextTo);
        
        // 高亮片段中所有匹配的词项
        if (highlightTerms != null && !highlightTerms.isEmpty()) {
            fragment = highlightTerms(fragment, highlightTerms, pre, post);
        }

        StringBuilder sb = new StringBuilder();
        
        // 添加前置省略号
        if (contextFrom > 0) {
            sb.append("...");
        }
        
        sb.append(fragment);
        
        // 添加后置省略号
        if (contextTo < content.length()) {
            sb.append("...");
        }
        
        return sb.toString();
    }

    /**
     * 使用标准Lucene高亮器生成高亮片段
     *
     * @param query 查询对象
     * @param content 文档内容
     * @param pre 高亮前缀标签
     * @param post 高亮后缀标签
     * @return 高亮片段，失败返回null
     */
    private String buildLuceneHighlight(Query query, String content, String pre, String post) {
        try {
            // 创建HTML格式化器
            SimpleHTMLFormatter formatter = new SimpleHTMLFormatter(pre, post);
            
            // 创建查询评分器，用于内容字段
            QueryScorer scorer = new QueryScorer(query, FIELD_CONTENT);
            
            // 创建片段器，控制片段大小
            Fragmenter fragmenter = new SimpleSpanFragmenter(scorer, HIGHLIGHT_FRAGMENT_SIZE);
            
            // 创建高亮器
            Highlighter highlighter = new Highlighter(formatter, scorer);
            highlighter.setTextFragmenter(fragmenter);
            
            // 生成最佳片段
            return highlighter.getBestFragment(analyzer, FIELD_CONTENT, content);
            
        } catch (IOException | InvalidTokenOffsetsException e) {
            log.warn("Lucene高亮生成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成默认摘要（截取前N个字符）
     *
     * @param content 文档内容
     * @return 默认摘要
     */
    private String buildDefaultSummary(String content) {
        if (content.length() <= DEFAULT_SUMMARY_LENGTH) {
            return content;
        }
        return content.substring(0, DEFAULT_SUMMARY_LENGTH) + "...";
    }

    /**
     * 过滤用于高亮显示的词项
     * <p>
     * 过滤策略：
     * - 移除空白、标点符号
     * - 移除停用词
     * - 移除无意义的单字符（非ASCII）
     * - 去重并保持顺序
     * </p>
     *
     * @param terms 原始词项列表
     * @return 过滤后的词项列表
     */
    private List<String> filterTermsForHighlight(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 使用LinkedHashSet保持顺序并去重
        LinkedHashSet<String> filteredSet = new LinkedHashSet<>();
        
        for (String term : terms) {
            // 跳过空白词项
            if (StringUtils.isBlank(term)) {
                continue;
            }
            
            // 跳过纯标点和空白
            if (term.matches(REGEX_WHITESPACE_PUNCT)) {
                continue;
            }
            
            // 跳过停用词
            if (PHRASE_STOP_WORDS.contains(term)) {
                continue;
            }
            
            // 跳过无意义的单字符（保留ASCII单字符）
            if (term.length() == MIN_SINGLE_CHAR_LENGTH && !isAsciiWord(term)) {
                continue;
            }
            
            filteredSet.add(term);
        }
        
        return new ArrayList<>(filteredSet);
    }

    /**
     * 根据锚点词项查找最佳高亮窗口
     * <p>
     * 算法步骤：
     * 1. 选择最佳锚点词项（优先长ASCII词）
     * 2. 找到锚点词项在内容中的所有出现位置
     * 3. 对每个位置构建窗口并计算包含的词项数
     * 4. 返回包含最多词项的窗口
     * </p>
     *
     * @param content 文档内容
     * @param terms 查询词项列表
     * @return 最佳窗口的起始和结束位置数组[start, end]，未找到返回null
     */
    private int[] findBestWindowByAnchor(String content, List<String> terms) {
        if (StringUtils.isBlank(content) || terms == null || terms.isEmpty()) {
            return null;
        }

        // 选择锚点词项
        String anchor = pickAnchorTerm(terms);
        if (StringUtils.isBlank(anchor)) {
            return null;
        }

        int bestScore = -1;
        int bestFrom = -1;
        int bestTo = -1;

        // 遍历锚点词项的所有出现位置
        int searchFromIndex = 0;
        while (true) {
            int anchorIndex = StringUtils.indexOfIgnoreCase(content, anchor, searchFromIndex);
            if (anchorIndex < 0) {
                break; // 没有更多匹配位置
            }

            // 以锚点为中心构建窗口
            int windowFrom = Math.max(0, anchorIndex - HIGHLIGHT_WINDOW_CONTEXT);
            int windowTo = Math.min(content.length(), anchorIndex + anchor.length() + HIGHLIGHT_WINDOW_CONTEXT);
            
            // 计算窗口内包含的词项数量
            String windowContent = content.substring(windowFrom, windowTo);
            int score = calculateWindowScore(windowContent, terms);
            
            // 更新最佳窗口
            if (score > bestScore) {
                bestScore = score;
                bestFrom = windowFrom;
                bestTo = windowTo;
            }

            // 移动到下一个搜索位置
            searchFromIndex = anchorIndex + anchor.length();
        }

        // 返回最佳窗口（如果找到）
        if (bestFrom >= 0 && bestTo > bestFrom) {
            return new int[]{bestFrom, bestTo};
        }
        
        return null;
    }

    /**
     * 计算窗口内包含的词项数量（评分）
     *
     * @param windowContent 窗口内容
     * @param terms 词项列表
     * @return 包含的词项数量
     */
    private int calculateWindowScore(String windowContent, List<String> terms) {
        int score = 0;
        for (String term : terms) {
            if (containsTerm(windowContent, term)) {
                score++;
            }
        }
        return score;
    }

    /**
     * 从词项列表中选择锚点词项
     * <p>
     * 选择策略：
     * 1. 优先选择最长的ASCII词项（便于忽略大小写匹配）
     * 2. 如果没有ASCII词项，则选择最长的词项
     * 3. 长词项更具有区分度，作为锚点更合适
     * </p>
     *
     * @param terms 词项列表
     * @return 锚点词项
     */
    private String pickAnchorTerm(List<String> terms) {
        String bestAsciiTerm = null;
        
        // 首先寻找最长的ASCII词项
        for (String term : terms) {
            if (isAsciiWord(term)) {
                if (bestAsciiTerm == null || term.length() > bestAsciiTerm.length()) {
                    bestAsciiTerm = term;
                }
            }
        }
        
        // 如果找到ASCII词项，优先使用
        if (bestAsciiTerm != null) {
            return bestAsciiTerm;
        }
        
        // 否则选择最长的词项
        String bestTerm = null;
        for (String term : terms) {
            if (bestTerm == null || term.length() > bestTerm.length()) {
                bestTerm = term;
            }
        }
        
        return bestTerm;
    }

    /**
     * 判断文本是否包含指定词项
     * <p>
     * 匹配策略：
     * - ASCII词项：忽略大小写匹配
     * - 非ASCII词项：精确匹配
     * </p>
     *
     * @param text 文本
     * @param term 词项
     * @return 如果包含返回true
     */
    private boolean containsTerm(String text, String term) {
        if (StringUtils.isBlank(text) || StringUtils.isBlank(term)) {
            return false;
        }
        
        if (isAsciiWord(term)) {
            // ASCII词项使用大小写不敏感匹配
            return StringUtils.indexOfIgnoreCase(text, term, 0) >= 0;
        } else {
            // 非ASCII词项使用精确匹配
            return text.contains(term);
        }
    }

    /**
     * 判断词项是否为ASCII单词
     * <p>
     * ASCII单词由字母、数字和常见符号组成，如：hello、test123、file.txt等
     * </p>
     *
     * @param term 词项
     * @return 如果是ASCII单词返回true
     */
    private boolean isAsciiWord(String term) {
        return term != null && term.matches(REGEX_ASCII_WORD);
    }

    /**
     * 在片段中高亮显示多个词项
     * <p>
     * 高亮策略：
     * - 按词项长度降序排序，避免短词覆盖长词的高亮
     * - 依次对每个词项进行高亮处理
     * - 跳过空白词项
     * </p>
     *
     * @param fragment 文本片段
     * @param terms 需要高亮的词项列表
     * @param pre 高亮前缀标签
     * @param post 高亮后缀标签
     * @return 高亮后的文本片段
     */
    private String highlightTerms(String fragment, List<String> terms, String pre, String post) {
        if (StringUtils.isBlank(fragment) || terms == null || terms.isEmpty()) {
            return fragment;
        }
        
        // 按长度降序排序，避免短词覆盖长词
        List<String> sortedTerms = new ArrayList<>(terms);
        sortedTerms.sort((term1, term2) -> {
            if (term1 == null && term2 == null) return 0;
            if (term1 == null) return 1;  // null排在后面
            if (term2 == null) return -1; // null排在后面
            return Integer.compare(term2.length(), term1.length()); // 长度降序
        });

        // 依次高亮每个词项
        String result = fragment;
        for (String term : sortedTerms) {
            if (StringUtils.isBlank(term)) {
                continue;
            }
            result = highlightSingleTerm(result, term, pre, post);
        }
        
        return result;
    }

    /**
     * 在文本中高亮显示单个词项
     * <p>
     * 使用正则表达式进行替换：
     * - ASCII词项：使用CASE_INSENSITIVE标志忽略大小写
     * - 非ASCII词项：使用精确匹配
     * - 对特殊字符进行转义以避免正则表达式错误
     * </p>
     *
     * @param text 文本
     * @param term 需要高亮的词项
     * @param pre 高亮前缀标签
     * @param post 高亮后缀标签
     * @return 高亮后的文本
     */
    private String highlightSingleTerm(String text, String term, String pre, String post) {
        // 根据词项类型选择正则标志
        int flags = isAsciiWord(term) ? Pattern.CASE_INSENSITIVE : 0;
        
        // 编译正则模式，转义特殊字符
        Pattern pattern = Pattern.compile(Pattern.quote(term), flags);
        Matcher matcher = pattern.matcher(text);
        
        // 使用StringBuffer进行替换（支持正则表达式引用）
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String matchedText = matcher.group();
            // 转义替换文本，避免正则表达式特殊字符干扰
            String replacement = Matcher.quoteReplacement(pre + matchedText + post);
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
}
