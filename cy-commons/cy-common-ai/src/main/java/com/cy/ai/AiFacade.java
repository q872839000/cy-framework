package com.cy.ai;

import com.cy.ai.config.AiProviderRegistry;
import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.ChatMessage;
import com.cy.ai.model.EmbeddingRequest;
import com.cy.ai.model.EmbeddingResponse;
import com.cy.ai.model.RerankRequest;
import com.cy.ai.model.RerankResponse;
import com.cy.ai.provider.AiProvider;
import com.cy.ai.thinking.ThinkingControlMode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 模型服务统一门面（Spring AI 风格）
 * <p>
 * 对外暴露的唯一公开 API 入口，封装了与各模型厂商交互的全部能力。
 * 所有业务模块（对话、意图分类、参数填充等）统一通过此 Facade 调用 AI 服务，
 * 无需关心底层使用的是哪个厂商或协议。
 * </p>
 * 支持三种 provider 解析方式：
 * <ul>
 *     <li><b>默认 provider</b>：不指定 provider，使用 {@code cy.ai.default-provider} 配置</li>
 *     <li><b>按 provider 名称</b>：通过字符串直接指定 {@code cy.ai.providers} 中的 key</li>
 *     <li><b>角色路由</b>：embedding / rerank 按 {@code cy.ai.roles.<role>} 路由，
 *     未配置时自动探测第一个具备对应能力的 provider</li>
 * </ul>
 * <p>
 * <b>动态性</b>：所有查询每次调用实时委托 {@link AiProviderRegistry} 当前快照——
 * 配置中心热更新的 provider 增删改、默认切换、角色路由调整即时生效，无需重启。
 * </p>
 * 使用示例：
 * <pre>{@code
 * // 使用默认 provider
 * aiFacade.chatStream(request);
 * aiFacade.simpleChat(systemPrompt, userMessage);
 *
 * // 按 provider 名称
 * aiFacade.chatSync("zhipu", request);
 * aiFacade.chatStream("modelverse", request);
 * }</pre>
 *
 * @author pcy
 * @date 2026/04/16
 * @see AiProvider
 * @see AiProviderRegistry
 */
public class AiFacade {

    /** Provider 动态注册表（全部查询委托当前快照） */
    private final AiProviderRegistry registry;

    /**
     * 构造 AI 门面
     *
     * @param registry Provider 动态注册表（由 {@link com.cy.ai.config.AiAutoConfiguration} 自动装配）
     */
    public AiFacade(AiProviderRegistry registry) {
        this.registry = registry;
    }

    // ========================= 默认 Provider —— 流式 =========================

    /**
     * 流式对话补全（默认 provider）
     * <p>
     * 使用配置文件中 {@code cy.ai.default-provider} 指定的 provider 发起流式请求。
     * 适用于对话界面的逐字输出等需要实时展示生成过程的场景。
     * </p>
     *
     * @param request 对话补全请求
     * @return 包含多个响应块的 Flux
     */
    public Flux<ChatCompletionChunk> chatStream(ChatCompletionRequest request) {
        return getDefaultProvider().chatCompletionStream(request);
    }

    // ========================= 默认 Provider —— 非流式 =========================

    /**
     * 非流式对话补全 - 响应式（默认 provider）
     * <p>
     * 使用配置文件中 {@code cy.ai.default-provider} 指定的 provider 发起非流式请求。
     * 返回 {@link Mono}，适用于响应式编程场景。
     * </p>
     *
     * @param request 对话补全请求
     * @return 包含完整响应的 Mono
     */
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request) {
        return getDefaultProvider().chatCompletion(request);
    }

    /**
     * 非流式对话补全 - 同步阻塞（默认 provider）
     * <p>
     * 使用配置文件中 {@code cy.ai.default-provider} 指定的 provider 发起非流式请求。
     * 阻塞等待响应，适用于非响应式编程场景。
     * </p>
     *
     * @param request 对话补全请求
     * @return 完整响应对象
     */
    public ChatCompletionResponse chatSync(ChatCompletionRequest request) {
        return chat(request).block();
    }

    // ========================= 指定 Provider —— 流式 =========================

    /**
     * 流式对话补全（指定 provider）
     * <p>
     * 使用指定的 provider 发起流式请求。
     * </p>
     *
     * @param providerName provider 名称（如 "zhipu"、"modelverse"）
     * @param request      对话补全请求
     * @return 包含多个响应块的 Flux
     */
    public Flux<ChatCompletionChunk> chatStream(String providerName, ChatCompletionRequest request) {
        return getProvider(providerName).chatCompletionStream(request);
    }

    // ========================= 指定 Provider —— 非流式 =========================

    /**
     * 非流式对话补全 - 响应式（指定 provider）
     * <p>
     * 使用指定的 provider 发起非流式请求。
     * 返回 {@link Mono}，适用于响应式编程场景。
     * </p>
     *
     * @param providerName provider 名称（如 "zhipu"、"modelverse"）
     * @param request      对话补全请求
     * @return 包含完整响应的 Mono
     */
    public Mono<ChatCompletionResponse> chat(String providerName, ChatCompletionRequest request) {
        return getProvider(providerName).chatCompletion(request);
    }

    /**
     * 非流式对话补全 - 同步阻塞（指定 provider）
     * <p>
     * 使用指定的 provider 发起非流式请求。
     * 阻塞等待响应，适用于非响应式编程场景。
     * </p>
     *
     * @param providerName provider 名称（如 "zhipu"、"modelverse"）
     * @param request      对话补全请求
     * @return 完整响应对象
     */
    public ChatCompletionResponse chatSync(String providerName, ChatCompletionRequest request) {
        return chat(providerName, request).block();
    }

    // ========================= 便捷方法 =========================

    /**
     * 简单对话（默认 provider，同步阻塞）
     * <p>
     * 快捷方法，适用于简单的单轮对话场景。
     * 内部构造 {@link ChatCompletionRequest}，阻塞等待响应。
     * </p>
     *
     * @param systemPrompt 系统提示词（可选，传 null 则不设置）
     * @param userMessage  用户消息
     * @return AI 回复的文本内容
     */
    public String simpleChat(String systemPrompt, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(userMessage));

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(messages)
                .build();

        ChatCompletionResponse response = chatSync(request);
        return response.getChoices().get(0).getMessage().getContent();
    }

    /**
     * 简单对话（指定 provider，同步阻塞）
     * <p>
     * 快捷方法，适用于简单的单轮对话场景。
     * 内部构造 {@link ChatCompletionRequest}，阻塞等待响应。
     * </p>
     *
     * @param providerName provider 名称（如 "zhipu"、"modelverse"）
     * @param systemPrompt 系统提示词（可选，传 null 则不设置）
     * @param userMessage  用户消息
     * @return AI 回复的文本内容
     */
    public String simpleChat(String providerName, String systemPrompt, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(userMessage));

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(messages)
                .build();

        ChatCompletionResponse response = chatSync(providerName, request);
        return response.getChoices().get(0).getMessage().getContent();
    }

    // ========================= 多模态便捷方法 =========================

    /**
     * 携带图片的简单对话（默认 provider，同步阻塞）
     * <p>
     * 用户消息按 OpenAI content parts 规范组装（文本块 + image_url 块）。
     * 调用方应先用 {@link #supportsVision(String)} 完成能力门控。
     * </p>
     *
     * @param systemPrompt 系统提示词（可选，传 null 则不设置）
     * @param userMessage  用户消息文本（可空，纯图片输入时传 null）
     * @param imageUrls    图片地址列表（data URI 或 http(s) URL）
     * @return AI 回复的文本内容
     */
    public String simpleChatWithImages(String systemPrompt, String userMessage, List<String> imageUrls) {
        return simpleChatWithImages(null, systemPrompt, userMessage, imageUrls);
    }

    /**
     * 携带图片的简单对话（指定 provider，同步阻塞）
     *
     * @param providerName provider 名称（null / 空白走默认 provider）
     * @param systemPrompt 系统提示词（可选，传 null 则不设置）
     * @param userMessage  用户消息文本（可空，纯图片输入时传 null）
     * @param imageUrls    图片地址列表（data URI 或 http(s) URL）
     * @return AI 回复的文本内容
     */
    public String simpleChatWithImages(String providerName, String systemPrompt,
                                       String userMessage, List<String> imageUrls) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.userWithImages(userMessage, imageUrls));

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(messages)
                .build();

        ChatCompletionResponse response = providerName == null || providerName.isBlank()
                ? chatSync(request) : chatSync(providerName, request);
        return response.getChoices().get(0).getMessage().getContent();
    }

    // ========================= Embedding =========================

    /**
     * Embedding 向量化（角色路由）
     * <p>
     * 按 {@link AiRole#EMBEDDING} 角色路由：优先取 {@code cy.ai.roles.embedding} 配置的
     * provider；未配置时自动选择第一个 {@link AiProvider#supportsEmbeddings()} 为 true
     * 的 provider；都没有则抛错（调用方应据此降级）。
     * </p>
     *
     * @param request Embedding 请求（model 可空，由 provider 默认值补齐）
     * @return 包含完整响应的 Mono
     */
    public Mono<EmbeddingResponse> embed(EmbeddingRequest request) {
        return registry.requireEmbeddingProvider().embeddings(request);
    }

    /**
     * Embedding 向量化（指定 provider）
     *
     * @param providerName provider 名称
     * @param request      Embedding 请求
     * @return 包含完整响应的 Mono
     */
    public Mono<EmbeddingResponse> embed(String providerName, EmbeddingRequest request) {
        return getProvider(providerName).embeddings(request);
    }

    /**
     * 单条文本向量化（角色路由，同步阻塞）
     *
     * @param text 文本
     * @return 向量
     */
    public float[] embedOne(String text) {
        EmbeddingResponse response = embed(EmbeddingRequest.builder()
                .input(List.of(text)).build()).block();
        if (response == null || response.embeddingOf(0) == null) {
            throw new IllegalStateException("Embedding 响应为空");
        }
        return response.embeddingOf(0);
    }

    /**
     * 批量文本向量化（角色路由，同步阻塞；返回与输入等长、按 input 顺序对齐的向量列表）
     *
     * @param texts 文本列表
     * @return 向量列表（与输入同序等长）
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingResponse response = embed(EmbeddingRequest.builder()
                .input(texts).build()).block();
        if (response == null) {
            throw new IllegalStateException("Embedding 响应为空");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            float[] v = response.embeddingOf(i);
            if (v == null) {
                throw new IllegalStateException("Embedding 响应缺少 index=" + i + " 的向量");
            }
            vectors.add(v);
        }
        return vectors;
    }

    /**
     * 是否有可用的 Embedding provider（调用方据此决定降级路径）。
     */
    public boolean hasEmbeddingProvider() {
        return registry.hasEmbeddingProvider();
    }

    // ========================= Rerank =========================

    /**
     * Rerank 重排（角色路由）
     * <p>
     * 按 {@link AiRole#RERANK} 角色路由：优先取 {@code cy.ai.roles.rerank} 配置的
     * provider；未配置时自动选择第一个 {@link AiProvider#supportsRerank()} 为 true
     * 的 provider；都没有则抛错（调用方应据此降级）。
     * </p>
     *
     * @param request Rerank 请求（model 可空，由 provider 默认值补齐）
     * @return 包含重排结果的 Mono
     */
    public Mono<RerankResponse> rerank(RerankRequest request) {
        return registry.requireRerankProvider().rerank(request);
    }

    /**
     * Rerank 重排（指定 provider）
     *
     * @param providerName provider 名称
     * @param request      Rerank 请求
     * @return 包含重排结果的 Mono
     */
    public Mono<RerankResponse> rerank(String providerName, RerankRequest request) {
        return getProvider(providerName).rerank(request);
    }

    /**
     * Rerank 重排（角色路由，同步阻塞）
     *
     * @param request Rerank 请求
     * @return 重排结果
     */
    public RerankResponse rerankSync(RerankRequest request) {
        return rerank(request).block();
    }

    /**
     * 是否有可用的 Rerank provider（调用方据此决定降级路径）。
     */
    public boolean hasRerankProvider() {
        return registry.hasRerankProvider();
    }

    // ========================= 模型清单 =========================

    /**
     * 查询指定 provider 的可用模型清单（OpenAI 兼容 {@code GET /v1/models}）
     * <p>
     * 管理面"拉取模型列表"入口；provider 不存在抛 40901，
     * 不支持该协议的实现抛 {@link UnsupportedOperationException}。
     * </p>
     *
     * @param providerName provider 名称
     * @return 模型清单响应的 Mono
     */
    public Mono<com.cy.ai.model.ModelListResponse> listModels(String providerName) {
        return getProvider(providerName).listModels();
    }

    /**
     * 查询默认 provider 的可用模型清单
     *
     * @return 模型清单响应的 Mono
     */
    public Mono<com.cy.ai.model.ModelListResponse> listModels() {
        return getDefaultProvider().listModels();
    }

    // ========================= 内部方法 =========================

    /**
     * 获取默认 provider 的思考控制模式。
     * <p>
     * 供 {@code LlmGateway} / {@code ModelHealthService} 等上层调用方查询当前默认 provider
     * 的思考控制协议，配合 {@link com.cy.ai.thinking.ThinkingPolicyTranslator} 使用。
     * </p>
     *
     * @return 默认 provider 的思考控制模式
     */
    public ThinkingControlMode getDefaultProviderThinkingControlMode() {
        return getDefaultProvider().thinkingControlMode();
    }

    /**
     * 获取默认 provider 的思考全局开关状态。
     * <p>
     * 供上层（如 {@code LlmGateway} 日志观测）查询当前默认 provider 是否开启了模型思考能力。
     * </p>
     *
     * @return {@code true} 表示开启思考；{@code false} 表示全局关闭
     */
    public boolean isDefaultProviderThinkingEnabled() {
        return getDefaultProvider().thinkingEnabled();
    }

    // ========================= 能力探测 =========================

    /**
     * 指定 provider 是否具备视觉（图片输入）能力
     * <p>
     * 供编排层在放行图片输入前做能力门控；provider 名不存在时返回 {@code false}
     * （按不具备能力处理，不抛错——调用方据此给出统一的"不支持图片"文案）。
     * </p>
     *
     * @param providerName provider 名称（{@code cy.ai.providers} 的 key）
     * @return true 表示该 provider 的 chat 模型接受图片内容块
     */
    public boolean supportsVision(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return isDefaultProviderVisionSupported();
        }
        AiProvider provider = registry.snapshot().providers().get(providerName);
        return provider != null && provider.supportsVision();
    }

    /**
     * 默认 provider 是否具备视觉（图片输入）能力
     *
     * @return true 表示默认 provider 的 chat 模型接受图片内容块
     */
    public boolean isDefaultProviderVisionSupported() {
        return getDefaultProvider().supportsVision();
    }

    /**
     * 获取默认 provider（委托注册表当前快照）
     */
    private AiProvider getDefaultProvider() {
        return registry.requireDefaultProvider();
    }

    /**
     * 获取指定 provider（委托注册表当前快照）
     */
    private AiProvider getProvider(String providerName) {
        return registry.requireProvider(providerName);
    }

    /**
     * 获取所有可用的 provider 名称
     */
    public List<String> getAvailableProviders() {
        return new ArrayList<>(registry.snapshot().providers().keySet());
    }

    /**
     * 获取默认 provider 名称
     */
    public String getDefaultProviderName() {
        return registry.snapshot().defaultProvider();
    }
}
