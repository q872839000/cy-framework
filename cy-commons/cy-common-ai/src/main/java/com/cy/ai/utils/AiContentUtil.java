package com.cy.ai.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 模型回复内容解析工具
 * <p>
 * 提供对大模型回复文本的结构化解析能力，包括：
 * <ul>
 *     <li>提取并移除 {@code <think>...</think>} 标签内的思考文本</li>
 *     <li>提取代码块（支持 ``` 和 ~~~ 围栏，可带语言标签）</li>
 *     <li>识别并解析 JSON（优先从代码块中检测，其次从纯文本中检测）</li>
 *     <li>获取去除标签和代码块后的纯文本</li>
 * </ul>
 * </p>
 * <p>
 * 所有方法均为无状态的静态方法，线程安全。
 * </p>
 *
 * @author pcy
 * @date 2026/03/09
 */
public final class AiContentUtil {

    /**
     * 匹配 {@code <think>...</think>} 标签
     * <p>
     * 非贪婪匹配，支持标签属性、内容换行，忽略大小写。
     * 用于提取模型的中间思考过程文本。
     * </p>
     */
    private static final Pattern THINK_PATTERN = Pattern.compile(
            "(?i)<think\\b[^>]*>([\\s\\S]*?)</think>",
            Pattern.DOTALL
    );

    /**
     * 匹配围栏代码块
     * <p>
     * 支持 ``` 和 ~~~ 两种围栏标记，语言标签可选。
     * 捕获组 1 为语言标签，捕获组 2 为代码内容。
     * </p>
     */
    private static final Pattern FENCE_CODE_PATTERN = Pattern.compile(
            "(?:```|~~~)\\s*([a-zA-Z0-9_+\\-]*)\\s*\\n?([\\s\\S]*?)(?:```|~~~)",
            Pattern.DOTALL
    );

    /** JSON 解析用 ObjectMapper（线程安全单例） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AiContentUtil() {
        /* 工具类禁止实例化 */
    }

    // ========================= 预处理方法 =========================

    /**
     * 移除模型回复中的 {@code <think>...</think>} 标签及其内容
     * <p>
     * 部分模型即使设置了 {@code enable_thinking=false}，仍可能输出思考标签。
     * 此方法用于在解析结构化输出前做安全清洗，确保下游解析逻辑不受干扰。
     * </p>
     *
     * @param content 原始模型回复文本，为 null 时返回空字符串
     * @return 移除思考标签后的文本
     */
    public static String stripThinkTags(String content) {
        if (content == null) {
            return "";
        }
        return THINK_PATTERN.matcher(content).replaceAll("").trim();
    }

    // ========================= 核心解析 =========================

    /**
     * 解析模型回复内容
     * <p>
     * 对原始回复文本进行结构化解析，按顺序执行以下步骤：
     * <ol>
     *     <li>提取并聚合所有 {@code <think>} 标签内的思考文本，然后移除这些标签</li>
     *     <li>提取所有围栏代码块（按出现顺序），然后移除代码块</li>
     *     <li>剩余文本折叠连续空白后作为纯文本</li>
     *     <li>尝试从代码块和纯文本中解析 JSON（优先标注为 json 的代码块）</li>
     * </ol>
     * </p>
     *
     * @param content 原始模型回复文本，为 null 时视为空字符串
     * @return 解析结果对象，包含思考内容、代码块列表、纯文本和 JSON 解析结果
     */
    public static ParseResult parse(String content) {
        if (content == null) {
            content = "";
        }
        String original = content;

        // 步骤 1：提取并聚合所有 think 标签内容
        StringBuilder thinkBuilder = new StringBuilder();
        Matcher thinkMatcher = THINK_PATTERN.matcher(content);
        while (thinkMatcher.find()) {
            if (!thinkBuilder.isEmpty()) {
                thinkBuilder.append("\n");
            }
            thinkBuilder.append(thinkMatcher.group(1).trim());
        }
        content = THINK_PATTERN.matcher(content).replaceAll("").trim();

        // 步骤 2：提取所有代码块
        List<CodeBlock> codeBlocks = new ArrayList<>();
        Matcher codeMatcher = FENCE_CODE_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            String lang = codeMatcher.group(1);
            String code = codeMatcher.group(2);
            codeBlocks.add(new CodeBlock(lang, code == null ? "" : code));
        }
        content = FENCE_CODE_PATTERN.matcher(content).replaceAll("").trim();

        // 步骤 3：生成纯文本（折叠连续空白）
        String plainText = content.replaceAll("\\s+", " ").trim();

        // 步骤 4：尝试解析 JSON
        JsonNode foundJson = tryExtractJson(codeBlocks, plainText);

        return new ParseResult(original, thinkBuilder.toString(),
                codeBlocks, plainText, foundJson);
    }

    // ========================= 便捷提取方法 =========================

    /**
     * 从回复中提取 JSON 字符串
     * <p>
     * 优先从标注为 json 的代码块中提取，其次尝试所有代码块，
     * 最后尝试纯文本。返回紧凑格式的 JSON 字符串。
     * </p>
     *
     * @param content 原始模型回复文本
     * @return 包含 JSON 字符串的 Optional，无有效 JSON 时为 empty
     */
    public static Optional<String> extractJsonString(String content) {
        ParseResult result = parse(content);
        return result.getJsonNode().map(JsonNode::toString);
    }

    /**
     * 从回复中提取 JsonNode
     * <p>
     * 解析策略同 {@link #extractJsonString(String)}，返回 Jackson {@link JsonNode} 对象，
     * 便于后续通过 JsonNode API 或 ObjectMapper 转换为目标类型。
     * </p>
     *
     * @param content 原始模型回复文本
     * @return 包含 JsonNode 的 Optional，无有效 JSON 时为 empty
     */
    public static Optional<JsonNode> extractJsonNode(String content) {
        return parse(content).getJsonNode();
    }

    /**
     * 获取指定语言的第一个代码块内容
     * <p>
     * 按出现顺序查找第一个匹配指定语言标签的代码块。
     * language 参数为 null 时匹配无语言标注的代码块。比较时忽略大小写。
     * </p>
     *
     * @param content  原始模型回复文本
     * @param language 语言标签（如 "json"、"python"、"java"），为 null 表示匹配无标注的代码块
     * @return 包含代码内容的 Optional，未找到时为 empty
     */
    public static Optional<String> extractFirstCodeByLang(String content, String language) {
        ParseResult result = parse(content);
        String langLower = language == null ? null : language.toLowerCase().trim();

        for (CodeBlock cb : result.getCodeBlocks()) {
            if (langLower == null) {
                if (cb.getLanguage().isEmpty()) {
                    return Optional.of(cb.getContent());
                }
            } else {
                if (cb.getLanguage().isPresent()
                        && langLower.equals(cb.getLanguage().get())) {
                    return Optional.of(cb.getContent());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 获取所有代码块（按出现顺序）
     *
     * @param content 原始模型回复文本
     * @return 不可修改的代码块列表
     */
    public static List<CodeBlock> extractAllCodeBlocks(String content) {
        return parse(content).getCodeBlocks();
    }

    // ========================= 内部辅助 =========================

    /**
     * 从代码块和纯文本中尝试提取 JSON
     * <p>
     * 优先级：标注为 json 的代码块 > 其他代码块 > 纯文本
     * </p>
     *
     * @param codeBlocks 代码块列表
     * @param plainText  纯文本内容
     * @return 解析成功的 JsonNode，全部失败时返回 null
     */
    private static JsonNode tryExtractJson(List<CodeBlock> codeBlocks, String plainText) {
        // 优先：标注为 json 语言的代码块
        for (CodeBlock cb : codeBlocks) {
            if (cb.getLanguage().isPresent()
                    && "json".equalsIgnoreCase(cb.getLanguage().get())) {
                JsonNode node = tryParseJson(cb.getContent());
                if (node != null) {
                    return node;
                }
            }
        }
        // 其次：任意代码块（可能未标注语言但内容是 JSON）
        for (CodeBlock cb : codeBlocks) {
            JsonNode node = tryParseJson(cb.getContent());
            if (node != null) {
                return node;
            }
        }
        // 最后：纯文本
        return tryParseJson(plainText);
    }

    /**
     * 尝试将字符串解析为 JSON
     * <p>
     * 快速前置检查：必须以 { 或 [ 开头才尝试解析，避免无效的反序列化开销。
     * </p>
     *
     * @param candidate 待解析的字符串
     * @return 解析成功的 JsonNode，失败或非 JSON 格式时返回 null
     */
    private static JsonNode tryParseJson(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    // ========================= 结果模型 =========================

    /**
     * 模型回复解析结果
     * <p>
     * 封装对原始回复文本的完整解析产物，包含思考内容、代码块列表、
     * 纯文本和 JSON 解析结果。所有字段均为不可变或不可修改的集合。
     * </p>
     */
    public static class ParseResult {

        /** 原始回复文本 */
        private final String original;

        /** 聚合的思考内容（多个 think 标签内容按顺序拼接，可能为空字符串） */
        private final String thinkContent;

        /** 代码块列表（按出现顺序，不可修改） */
        private final List<CodeBlock> codeBlocks;

        /** 纯文本（移除 think 标签和代码块后，连续空白折叠为单空格） */
        private final String plainText;

        /** JSON 解析结果（可能为 null） */
        private final JsonNode jsonNode;

        ParseResult(String original, String thinkContent,
                    List<CodeBlock> codeBlocks, String plainText, JsonNode jsonNode) {
            this.original = original;
            this.thinkContent = thinkContent == null ? "" : thinkContent;
            this.codeBlocks = codeBlocks == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(codeBlocks);
            this.plainText = plainText == null ? "" : plainText;
            this.jsonNode = jsonNode;
        }

        /**
         * 获取原始回复文本
         *
         * @return 原始文本
         */
        public String getOriginal() {
            return original;
        }

        /**
         * 获取代码块列表
         *
         * @return 不可修改的代码块列表
         */
        public List<CodeBlock> getCodeBlocks() {
            return codeBlocks;
        }

        /**
         * 获取纯文本
         *
         * @return 纯文本字符串
         */
        public String getPlainText() {
            return plainText;
        }

        /**
         * 获取思考内容
         *
         * @return 包含思考文本的 Optional，无思考内容时为 empty
         */
        public Optional<String> getThinkContent() {
            return Optional.ofNullable(thinkContent).filter(s -> !s.isEmpty());
        }

        /**
         * 获取 JSON 解析结果
         *
         * @return 包含 JsonNode 的 Optional，无有效 JSON 时为 empty
         */
        public Optional<JsonNode> getJsonNode() {
            return Optional.ofNullable(jsonNode);
        }

        /**
         * 是否包含有效的 JSON
         *
         * @return true 表示成功解析出 JSON
         */
        public boolean hasValidJson() {
            return jsonNode != null;
        }

        /**
         * 获取最有效的内容
         * <p>
         * 优先级：JSON 字符串 > 第一个代码块内容 > 纯文本。
         * 适用于需要从模型回复中提取结构化数据的场景。
         * </p>
         *
         * @return 最有效的内容字符串
         */
        public String getEffectiveContent() {
            if (jsonNode != null) {
                return jsonNode.toString();
            }
            if (!codeBlocks.isEmpty()) {
                return codeBlocks.getFirst().getContent();
            }
            return plainText;
        }

        @Override
        public String toString() {
            return String.format(
                    "ParseResult{think=%s, codeBlocks=%d, plainText=%d chars, hasJson=%s}",
                    getThinkContent().map(s -> s.length() + " chars").orElse("none"),
                    codeBlocks.size(),
                    plainText.length(),
                    jsonNode != null
            );
        }
    }

    /**
     * 代码块
     * <p>
     * 表示模型回复中的一个围栏代码块，包含可选的语言标签和代码内容。
     * </p>
     */
    public static class CodeBlock {

        /** 语言标签（小写，可能为 null） */
        private final String language;

        /** 代码内容 */
        private final String content;

        CodeBlock(String language, String content) {
            this.language = (language == null || language.trim().isEmpty())
                    ? null : language.trim().toLowerCase();
            this.content = content == null ? "" : content;
        }

        /**
         * 获取语言标签
         *
         * @return 包含语言标签的 Optional（小写），无标注时为 empty
         */
        public Optional<String> getLanguage() {
            return Optional.ofNullable(language);
        }

        /**
         * 获取代码内容
         *
         * @return 代码内容字符串
         */
        public String getContent() {
            return content;
        }

        @Override
        public String toString() {
            return String.format("CodeBlock{lang=%s, len=%d}",
                    language == null ? "none" : language, content.length());
        }
    }
}
