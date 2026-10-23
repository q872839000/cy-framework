package com.cy.ai.config;

import com.cy.ai.model.StreamOptions;
import com.cy.ai.thinking.ThinkingControlMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模块配置属性（对齐 Spring AI 设计）
 * <p>
 * 参考 Spring AI 的配置风格，每个 provider 一个独立配置块：
 * <pre>
 * cy:
 *   ai:
 *     zhipu:
 *       api-key: xxx
 *       base-url: https://open.bigmodel.cn/api/paas
 *       chat:
 *         options:
 *           model: glm-4-air
 *           temperature: 0.7
 * </pre>
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
@Data
@ConfigurationProperties(prefix = AiProperties.PREFIX)
public class AiProperties {

    /** 配置前缀（{@code cy.ai}），供属性绑定与刷新监听共用。 */
    public static final String PREFIX = "cy.ai";

    /**
     * 默认使用的 provider 名称
     */
    private String defaultProvider;

    /**
     * 各个 AI 提供商的配置
     * key: provider 名称（如 zhipu、openai、custom）
     * value: provider 配置
     */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    /**
     * 角色路由：{@link com.cy.ai.AiRole} 角色 id → provider 名称。
     * <p>
     * 示例：{@code cy.ai.roles.embedding: local} 将 embedding 角色路由到 local provider。
     * 角色未配置时，embedding 调用自动选择第一个具备 embedding 能力的 provider。
     * </p>
     */
    private Map<String, String> roles = new LinkedHashMap<>();

    /**
     * AI 提供商配置（对齐 Spring AI 的 provider 配置结构）
     */
    @Data
    public static class ProviderConfig {
        /**
         * API 密钥
         */
        private String apiKey;

        /**
         * API 基础 URL
         */
        private String baseUrl;

        /**
         * API 版本（可选，某些厂商需要）
         */
        private String apiVersion;

        /**
         * 连接超时
         */
        private Duration connectTimeout = Duration.ofSeconds(5);

        /**
         * 读取超时
         */
        private Duration readTimeout = Duration.ofSeconds(60);

        /**
         * 最大连接数
         */
        private int maxConnections = 100;

        /**
         * Chat 模型配置
         */
        private ChatConfig chat = new ChatConfig();

        /**
         * Embedding 模型配置
         */
        private EmbeddingConfig embedding = new EmbeddingConfig();

        /**
         * Rerank 重排模型配置
         */
        private RerankConfig rerank = new RerankConfig();
    }

    /**
     * Embedding 模型配置（对齐 chat 配置结构）
     * <p>
     * 配置示例：
     * <pre>
     * cy:
     *   ai:
     *     providers:
     *       local:
     *         embedding:
     *           enabled: true
     *           options:
     *             model: bge-m3
     * </pre>
     * </p>
     */
    @Data
    public static class EmbeddingConfig {
        /**
         * 是否启用 embedding 能力（默认 false，未启用时 provider.supportsEmbeddings() 为 false）
         */
        private boolean enabled = false;

        /**
         * Embedding 选项
         */
        private EmbeddingOptions options = new EmbeddingOptions();
    }

    /**
     * Embedding 选项
     */
    @Data
    public static class EmbeddingOptions {
        /**
         * Embedding 模型名称（如 bge-m3、text-embedding-3-small）
         */
        private String model;
    }

    /**
     * Rerank 重排模型配置（对齐 embedding 配置结构）
     * <p>
     * 配置示例：
     * <pre>
     * cy:
     *   ai:
     *     providers:
     *       local:
     *         rerank:
     *           enabled: true
     *           options:
     *             model: bge-reranker-v2-m3
     * </pre>
     * </p>
     */
    @Data
    public static class RerankConfig {
        /**
         * 是否启用 rerank 能力（默认 false，未启用时 provider.supportsRerank() 为 false）
         */
        private boolean enabled = false;

        /**
         * Rerank 选项
         */
        private RerankOptions options = new RerankOptions();
    }

    /**
     * Rerank 选项
     */
    @Data
    public static class RerankOptions {
        /**
         * Rerank 模型名称（如 bge-reranker-v2-m3、rerank-multilingual-v3.0）
         */
        private String model;
    }

    /**
     * Chat 模型配置（对齐 Spring AI 的 chat 配置结构）
     */
    @Data
    public static class ChatConfig {
        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 是否具备视觉（图片输入）能力
         * <p>
         * 配置 key：{@code cy.ai.providers.<id>.chat.vision-enabled}，默认 {@code false}。
         * 声明该 provider 的 chat 模型支持 OpenAI 多模态 content parts（image_url）输入
         * （如 vLLM 部署的 Qwen-VL 系列、GPT-4o 等）。上层据此放行 / 拦截图片输入，
         * 模型目录（/ai/catalog）同步暴露给前端做输入入口的能力门控。
         * </p>
         * <p>
         * 粒度说明：本标志作用于 provider 的 chat 默认模型；会话级模型覆盖指向
         * 同 provider 其他模型时以其自身能力为准（服务端不做逐模型校验）。
         * </p>
         */
        private boolean visionEnabled = false;

        /**
         * 可用对话模型清单
         * <p>
         * 配置 key：{@code cy.ai.providers.<id>.chat.models}，默认空。
         * 管理页"拉取模型列表"（OpenAI {@code GET /models}）勾选后回写；
         * 模型目录（/ai/catalog）据此向前端提供 provider → 模型的分组选择。
         * 为空时目录回退为仅默认模型（{@code chat.options.model}）。
         * </p>
         */
        private java.util.List<String> models = new java.util.ArrayList<>();

        /**
         * Chat 选项（模型参数）
         */
        private ChatOptions options = new ChatOptions();
    }

    /**
     * Chat 选项（对齐 Spring AI 的 options 配置结构）
     */
    @Data
    public static class ChatOptions {
        /**
         * 模型名称（如 glm-4-air、gpt-4）
         */
        private String model;

        /**
         * 最大 token 数
         */
        private Integer maxTokens;

        /**
         * 温度参数
         */
        private Double temperature = 0.7;

        /**
         * Top P 参数
         */
        private Double topP;

        /**
         * 停止词
         */
        private String[] stop;

        /**
         * 推理强度默认档位（OpenAI 规范标准参数 reasoning_effort）
         * <p>
         * provider 级默认：本 provider 的所有请求未单独指定时按此档位下发
         * （minimal / low / medium / high）。不配置（null）则不下发该参数，
         * 思考行为保持模型默认。请求级取值优先于本默认值。
         * </p>
         */
        private String reasoningEffort;

        /**
         * 流式响应选项
         * 对应 OpenAI Chat Completions 请求体中的 stream_options。
         */
        private StreamOptions streamOptions;

        /**
         * 厂商扩展参数
         * 用于配置不属于 OpenAI 标准字段的厂商私有请求参数，序列化时会平铺到请求体根节点。
         */
        private Map<String, Object> extraParams = new LinkedHashMap<>();

        /**
         * 厂商思考控制模式：声明此 provider 使用哪种 wire 协议控制思考开关。
         * <p>
         * 配置 key：{@code cy.ai.providers.<id>.chat.options.thinking-control-mode}。
         * 取值：{@code STANDARD_REASONING_EFFORT}（推荐，网关 / DeepSeek 官方） /
         * {@code GLM_THINKING_TYPE}（智谱直连 GLM-4.5~4.7） /
         * {@code QWEN_ENABLE_THINKING}（DashScope / vLLM 直连） /
         * {@code DEEPSEEK_THINKING_TYPE}（已废弃并轨 STANDARD） / {@code NONE}（默认，无思考控制）。
         * </p>
         * <p>
         * 仅声明 wire 协议格式，不控制开/关——开/关由 {@link #thinkingEnabled} 决定。
         * 两者配合：{@code thinkingEnabled=false} + {@code thinkingControlMode=STANDARD_REASONING_EFFORT}
         * → 框架自动注入 {@code reasoning_effort=none}。
         * </p>
         */
        private ThinkingControlMode thinkingControlMode = ThinkingControlMode.NONE;

        /**
         * 是否开启模型思考（推理）能力——provider 级全局开关。
         * <p>
         * 配置 key：{@code cy.ai.providers.<id>.chat.options.thinking-enabled}。
         * </p>
         * <p>
         * {@code false} 时，框架在构建每个请求时按 {@link #thinkingControlMode} 自动注入关思考参数
         * （STANDARD 注入 {@code reasoning_effort=none}、GLM 注入 {@code thinking.type=disabled}、
         * Qwen 注入 {@code enable_thinking=false}），对所有请求全局生效。取代旧的手写
         * {@code extra-params: {enable_thinking: false}} 方式——后者需要知道每个厂商的 wire 参数名，
         * 前者只需声明语义开关。
         * </p>
         * <p>
         * <b>与 Agent 阶段级控制的关系</b>：Agent 层的 {@code LlmCallPurpose.FORCE_OFF}
         * 可以覆盖此处的 {@code true}（即使 provider 配了开思考，机械型阶段仍强制关）；
         * {@code INHERIT} 阶段则跟随此处的配置。
         * </p>
         */
        private boolean thinkingEnabled = false;
    }
}
