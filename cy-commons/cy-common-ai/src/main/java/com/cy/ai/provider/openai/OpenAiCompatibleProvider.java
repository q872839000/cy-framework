package com.cy.ai.provider.openai;

import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.EmbeddingRequest;
import com.cy.ai.model.EmbeddingResponse;
import com.cy.ai.model.ModelListResponse;
import com.cy.ai.model.RerankRequest;
import com.cy.ai.model.RerankResponse;
import com.cy.ai.provider.AiProvider;
import com.cy.ai.thinking.ThinkingControlMode;
import com.cy.ai.thinking.ThinkingPolicy;
import com.cy.ai.thinking.ThinkingPolicyTranslator;
import com.cy.core.exception.CYException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI 兼容协议 Provider（Spring AI 风格）
 * <p>
 * 覆盖所有遵循 OpenAI Chat Completions API 规范的模型厂商，包括但不限于：
 * <ul>
 *     <li>OpenAI 官方</li>
 *     <li>DeepSeek（深度求索）</li>
 *     <li>Kimi / Moonshot（月之暗面）</li>
 *     <li>GLM / ZhipuAI（智谱清言）</li>
 *     <li>通义千问 / DashScope</li>
 *     <li>本地 vLLM / Ollama 等</li>
 * </ul>
 * 不同厂商仅通过配置区分（baseUrl / apiKey / modelId），无需额外编码。
 * </p>
 * <p>
 * <b>设计理念（对齐 Spring AI）：</b>
 * <ul>
 *     <li>有状态：每个 provider 实例持有自己的连接配置和默认参数</li>
 *     <li>独立实例：每个配置块（cy.ai.zhipu、cy.ai.modelverse）创建独立实例</li>
 *     <li>可复用：同一 provider 的多次调用共享 WebClient 连接池</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    /**
     * Provider 名称，对应配置中的 provider key。
     */
    private final String name;

    /**
     * API 基础 URL。
     */
    private final String baseUrl;

    /**
     * API 认证密钥。
     */
    private final String apiKey;

    /**
     * API 版本路径前缀。
     */
    private final String apiVersion;

    /**
     * 默认模型名称。
     */
    private final String defaultModel;

    /**
     * 默认最大生成 token 数。
     */
    private final Integer defaultMaxTokens;

    /**
     * 默认温度参数。
     */
    private final Double defaultTemperature;

    /**
     * 默认 Top P 参数。
     */
    private final Double defaultTopP;

    /**
     * 默认推理强度档位（OpenAI 规范 reasoning_effort；null 表示不下发、保持模型默认）。
     */
    private final String defaultReasoningEffort;

    /**
     * 默认停止词。
     */
    private final List<String> defaultStop;

    /**
     * 默认流式响应选项。
     */
    private final com.cy.ai.model.StreamOptions defaultStreamOptions;

    /**
     * 默认厂商扩展参数。
     */
    private final Map<String, Object> defaultExtraParams;

    /**
     * Embedding 默认模型（null 表示此 provider 不提供 embedding 能力）。
     */
    private final String embeddingModel;

    /**
     * Rerank 默认模型（null 表示此 provider 不提供 rerank 能力）。
     */
    private final String rerankModel;

    /**
     * 此 provider 的思考控制模式：声明用哪种 wire 协议控制思考开关。
     * <p>
     * 由配置注入，供 {@link com.cy.ai.thinking.ThinkingPolicyTranslator} 将厂商无关的
     * {@link com.cy.ai.thinking.ThinkingPolicy} 翻译为具体请求参数。
     * </p>
     */
    private final ThinkingControlMode thinkingControlMode;

    /**
     * 是否开启模型思考能力——provider 级全局开关。
     * <p>
     * {@code false} 时，{@link #enrichRequest} 在构建每个请求后自动注入关思考参数
     * （按 {@link #thinkingControlMode} 翻译），对所有请求全局生效。
     * </p>
     */
    private final boolean thinkingEnabled;

    /**
     * 是否具备视觉（图片输入）能力——chat 模型接受 OpenAI 多模态 content parts
     * （image_url）输入。由配置 {@code cy.ai.providers.<id>.chat.vision-enabled} 注入。
     */
    private final boolean visionEnabled;

    /**
     * 当前 Provider 专用 WebClient。
     */
    private final WebClient webClient;

    /**
     * response_format 端点协商结果：实测拒绝该参数的模型集合（错误体点名 response_format 的 400 才记录）。
     * 命中后同模型请求直接剥离 response_format 发送，不再重复支付 400 探测成本。
     */
    private final Set<String> responseFormatRejectedModels = ConcurrentHashMap.newKeySet();

    /**
     * 构造 OpenAI 兼容 Provider
     *
     * @param name              provider 名称（如 zhipu、modelverse）
     * @param baseUrl           API 基础 URL
     * @param apiKey            API 密钥
     * @param apiVersion        API 版本（可选）
     * @param defaultModel      默认模型名称
     * @param defaultMaxTokens  默认最大 token 数
     * @param defaultTemperature 默认温度参数
     * @param defaultTopP       默认 Top P 参数
     * @param defaultReasoningEffort 默认推理强度档位（OpenAI 规范 reasoning_effort，可为 null）
     * @param defaultStop       默认停止词
     * @param defaultStreamOptions 默认流式响应选项
     * @param defaultExtraParams 默认厂商扩展参数
     * @param embeddingModel    Embedding 默认模型（null = 不提供 embedding 能力）
     * @param rerankModel       Rerank 默认模型（null = 不提供 rerank 能力）
     * @param thinkingControlMode 厂商思考控制模式（可为 null，按 NONE 处理）
     * @param thinkingEnabled   是否开启模型思考能力（provider 级全局开关）
     * @param visionEnabled     是否具备视觉（图片输入）能力
     * @param webClient         已配置的 WebClient 实例
     */
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey,
                                   String apiVersion, String defaultModel,
                                   Integer defaultMaxTokens, Double defaultTemperature,
                                   Double defaultTopP, String defaultReasoningEffort,
                                   List<String> defaultStop,
                                   com.cy.ai.model.StreamOptions defaultStreamOptions,
                                   Map<String, Object> defaultExtraParams,
                                   String embeddingModel,
                                   String rerankModel,
                                   ThinkingControlMode thinkingControlMode,
                                   boolean thinkingEnabled,
                                   boolean visionEnabled,
                                   WebClient webClient) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        // apiVersion 缺省按 OpenAI 标准 v1 兜底（空值会拼出 /null/xxx 路径）
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? "v1" : apiVersion;
        this.defaultModel = defaultModel;
        this.defaultMaxTokens = defaultMaxTokens;
        this.defaultTemperature = defaultTemperature;
        this.defaultTopP = defaultTopP;
        this.defaultReasoningEffort = defaultReasoningEffort;
        this.defaultStop = defaultStop;
        this.defaultStreamOptions = defaultStreamOptions;
        this.defaultExtraParams = defaultExtraParams;
        this.embeddingModel = embeddingModel;
        this.rerankModel = rerankModel;
        this.thinkingControlMode = thinkingControlMode != null ? thinkingControlMode : ThinkingControlMode.NONE;
        this.thinkingEnabled = thinkingEnabled;
        this.visionEnabled = visionEnabled;
        this.webClient = webClient;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ThinkingControlMode thinkingControlMode() {
        return thinkingControlMode;
    }

    @Override
    public boolean thinkingEnabled() {
        return thinkingEnabled;
    }

    @Override
    public boolean supportsVision() {
        return visionEnabled;
    }

    /**
     * 非流式对话补全
     * <p>
     * 委托 {@link OpenAiClient} 执行实际的 HTTP 通信。
     * 如果请求中未指定模型参数，使用 provider 的默认值。
     * </p>
     *
     * @param request 对话补全请求
     * @return 包含完整响应的 Mono
     */
    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        // 补齐默认参数
        ChatCompletionRequest enrichedRequest = applyFormatNegotiation(enrichRequest(request));

        log.debug("[{}] 非流式请求: model={}, messageCount={}",
                name, enrichedRequest.getModel(),
                enrichedRequest.getMessages() != null ? enrichedRequest.getMessages().size() : 0);

        Mono<ChatCompletionResponse> call = OpenAiClient.chatCompletion(
                webClient,
                apiVersion,
                apiKey,
                enrichedRequest
        );
        if (enrichedRequest.getResponseFormat() == null) {
            return call;
        }
        // 端点 400 拒绝 response_format 时剥离约束降级重试一次；缓存仅限错误体点名 response_format 的情形
        return call.onErrorResume(OpenAiCompatibleProvider::isBadRequest, e -> {
            markFormatRejectedIfConfirmed(e, enrichedRequest.getModel());
            enrichedRequest.setResponseFormat(null);
            return OpenAiClient.chatCompletion(webClient, apiVersion, apiKey, enrichedRequest);
        });
    }

    /**
     * 流式对话补全
     * <p>
     * 委托 {@link OpenAiClient} 执行实际的 HTTP 通信。
     * 如果请求中未指定模型参数，使用 provider 的默认值。
     * </p>
     *
     * @param request 对话补全请求
     * @return 包含多个响应块的 Flux
     */
    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
        // 补齐默认参数
        ChatCompletionRequest enrichedRequest = applyFormatNegotiation(enrichRequest(request));

        log.debug("[{}] 流式请求: model={}, messageCount={}",
                name, enrichedRequest.getModel(),
                enrichedRequest.getMessages() != null ? enrichedRequest.getMessages().size() : 0);

        Flux<ChatCompletionChunk> call = OpenAiClient.chatCompletionStream(
                webClient,
                apiVersion,
                apiKey,
                enrichedRequest
        );
        if (enrichedRequest.getResponseFormat() == null) {
            return call;
        }
        // 400 发生在响应头阶段、尚无 chunk 发射，剥离 response_format 重发是安全的
        return call.onErrorResume(OpenAiCompatibleProvider::isBadRequest, e -> {
            markFormatRejectedIfConfirmed(e, enrichedRequest.getModel());
            enrichedRequest.setResponseFormat(null);
            return OpenAiClient.chatCompletionStream(webClient, apiVersion, apiKey, enrichedRequest);
        });
    }

    @Override
    public boolean supportsEmbeddings() {
        return embeddingModel != null && !embeddingModel.isBlank();
    }

    /**
     * Embedding 向量化
     * <p>
     * 委托 {@link OpenAiClient} 执行 HTTP 通信；请求未指定模型时用
     * {@code cy.ai.providers.<id>.embedding.options.model} 的默认值。
     * </p>
     */
    @Override
    public Mono<EmbeddingResponse> embeddings(EmbeddingRequest request) {
        if (!supportsEmbeddings()) {
            return Mono.error(new UnsupportedOperationException(
                    "Provider '" + name + "' 未配置 embedding 模型（cy.ai.providers." + name
                            + ".embedding.options.model）"));
        }
        EmbeddingRequest enriched = EmbeddingRequest.builder()
                .model(request.getModel() != null ? request.getModel() : embeddingModel)
                .input(request.getInput())
                .dimensions(request.getDimensions())
                .encodingFormat(request.getEncodingFormat())
                .user(request.getUser())
                .build();

        log.debug("[{}] Embedding 请求: model={}, inputCount={}",
                name, enriched.getModel(),
                enriched.getInput() == null ? 0 : enriched.getInput().size());

        return OpenAiClient.embeddings(webClient, apiVersion, apiKey, enriched);
    }

    @Override
    public boolean supportsRerank() {
        return rerankModel != null && !rerankModel.isBlank();
    }

    /**
     * Rerank 重排
     * <p>
     * 委托 {@link OpenAiClient} 执行 HTTP 通信；请求未指定模型时用
     * {@code cy.ai.providers.<id>.rerank.options.model} 的默认值。
     * </p>
     */
    @Override
    public Mono<RerankResponse> rerank(RerankRequest request) {
        if (!supportsRerank()) {
            return Mono.error(new CYException(40907, name));
        }
        RerankRequest enriched = RerankRequest.builder()
                .model(request.getModel() != null ? request.getModel() : rerankModel)
                .query(request.getQuery())
                .documents(request.getDocuments())
                .topN(request.getTopN())
                .build();

        log.debug("[{}] Rerank 请求: model={}, docCount={}",
                name, enriched.getModel(),
                enriched.getDocuments() == null ? 0 : enriched.getDocuments().size());

        return OpenAiClient.rerank(webClient, apiVersion, apiKey, enriched);
    }

    /**
     * 模型列表查询
     * <p>
     * 委托 {@link OpenAiClient} 执行 GET {@code /v1/models}；管理面"拉取模型列表"用。
     * </p>
     */
    @Override
    public Mono<ModelListResponse> listModels() {
        return OpenAiClient.listModels(webClient, apiVersion, apiKey);
    }

    /**
     * 补齐请求参数（使用 provider 的默认值）
     * <p>
     * 参考 Spring AI 的设计：请求参数优先，未指定时使用 provider 配置的默认值。
     * 厂商扩展参数会先加载 provider 配置中的默认值，再叠加本次请求的 extraParams；
     * 当 key 重复时，本次请求的值覆盖配置默认值。
     * </p>
     * <p>
     * {@code reasoning_effort} 防重复键守卫：该参数既有标准字段（{@code reasoningEffort}）又可能被
     * 调用方按旧习惯写进 extraParams——两者同时存在会让 {@code @JsonAnyGetter} 平铺出重复 JSON 键。
     * 约定<b>显式 extras 优先</b>（调用方逐请求写的扩展参数比 provider 级默认档位意图更明确）：
     * extras 含该键时，标准字段让位（置 null），extras 值作为唯一来源下发。
     * </p>
     * <p>包私有以便单元测试直接驱动合并规则。</p>
     */
    ChatCompletionRequest enrichRequest(ChatCompletionRequest request) {
        Map<String, Object> mergedExtraParams = new LinkedHashMap<>();
        if (defaultExtraParams != null) {
            mergedExtraParams.putAll(defaultExtraParams);
        }
        if (request.getExtraParams() != null) {
            mergedExtraParams.putAll(request.getExtraParams());
        }

        String reasoningEffort = request.getReasoningEffort() != null
                ? request.getReasoningEffort() : defaultReasoningEffort;
        if (mergedExtraParams.containsKey(REASONING_EFFORT_WIRE_KEY)) {
            reasoningEffort = null;
        }

        ChatCompletionRequest enriched = ChatCompletionRequest.builder()
                .model(request.getModel() != null ? request.getModel() : defaultModel)
                .messages(request.getMessages())
                // max_completion_tokens 与 max_tokens 互斥：显式设置新标准参数时，
                // 旧参数（含 provider 默认值）整体让位，避免严格端点因二者同发报 400
                .maxCompletionTokens(request.getMaxCompletionTokens())
                .maxTokens(request.getMaxCompletionTokens() != null ? null
                        : (request.getMaxTokens() != null ? request.getMaxTokens() : defaultMaxTokens))
                .temperature(request.getTemperature() != null ? request.getTemperature() : defaultTemperature)
                .topP(request.getTopP() != null ? request.getTopP() : defaultTopP)
                .reasoningEffort(reasoningEffort)
                .thinkingLevel(request.getThinkingLevel())
                .frequencyPenalty(request.getFrequencyPenalty())
                .presencePenalty(request.getPresencePenalty())
                .n(request.getN())
                .stream(request.getStream())
                .streamOptions(request.getStreamOptions() != null ? request.getStreamOptions() : defaultStreamOptions)
                .stop(request.getStop() != null ? request.getStop() : defaultStop)
                .seed(request.getSeed())
                .responseFormat(request.getResponseFormat())
                .user(request.getUser())
                .tools(request.getTools())
                .toolChoice(request.getToolChoice())
                .extraParams(mergedExtraParams)
                .build();

        // 请求级思考偏好（页面控制）优先：非 auto 档位按 thinkingControlMode 翻译，覆盖全局开关。
        // auto / 空档位视为未指定，回退 provider 级全局开关：thinkingEnabled=false 注入关思考参数。
        // 复用 ThinkingPolicyTranslator 保证 wire 协议翻译的唯一来源——与 Agent 层 FORCE_OFF 走同一翻译路径。
        String thinkingLevel = enriched.getThinkingLevel();
        boolean hasLevelOverride = thinkingLevel != null && !thinkingLevel.isBlank()
                && !"auto".equalsIgnoreCase(thinkingLevel.trim());
        if (hasLevelOverride) {
            ThinkingPolicyTranslator.applyLevel(thinkingLevel, thinkingControlMode, enriched);
        } else if (!thinkingEnabled) {
            ThinkingPolicyTranslator.apply(ThinkingPolicy.DISABLED, thinkingControlMode, enriched);
        }
        return enriched;
    }

    /** reasoning_effort 在请求体里的标准键名（防重复键守卫用）。 */
    static final String REASONING_EFFORT_WIRE_KEY = "reasoning_effort";

    /**
     * 协商前置处理：该模型已被实测拒绝 response_format 时，直接剥离约束再发送。
     */
    private ChatCompletionRequest applyFormatNegotiation(ChatCompletionRequest enrichedRequest) {
        String model = enrichedRequest.getModel();
        if (enrichedRequest.getResponseFormat() != null && model != null
                && responseFormatRejectedModels.contains(model)) {
            enrichedRequest.setResponseFormat(null);
            log.debug("[{}] 端点已协商为不支持 response_format，直接降级发送: model={}", name, model);
        }
        return enrichedRequest;
    }

    /**
     * 确认由 response_format 引发的 400 才记录协商结果。
     * 上下文超长等无关 400 只降级重试不沉淀，避免误关停该模型的格式约束。
     */
    private void markFormatRejectedIfConfirmed(Throwable e, String model) {
        if (!isFormatRejection(e)) {
            log.warn("[{}] 请求 400（错误体未指明 response_format），本次剥离格式约束重试但不沉淀协商结果: model={}",
                    name, model);
            return;
        }
        if (model == null) {
            return;
        }
        if (responseFormatRejectedModels.add(model)) {
            log.warn("[{}] 端点拒绝 response_format（HTTP 400），降级为无格式约束重试，后续同模型请求免探测: model={}",
                    name, model);
        }
    }

    /**
     * 判断 400 是否确由 response_format 引发：厂商错误体摘要在 CYException args[1]，点名该参数才认定。
     */
    private static boolean isFormatRejection(Throwable e) {
        if (!(e instanceof CYException cy) || cy.getArgs().length < 2
                || !(cy.getArgs()[1] instanceof String body)) {
            return false;
        }
        String flat = body.toLowerCase(Locale.ROOT);
        return flat.contains("response_format") || flat.contains("responseformat");
    }

    /**
     * 判断是否 HTTP 400。OpenAiClient 把厂商错误体封进 CYException（40101），args[0] 为状态码。
     * 与 Python 引擎（chat_llm.py）同口径：任何 400 都先按格式约束被拒处理，降级重试一次；
     * 其余 400 重试后仍原样失败，行为与未降级一致。
     */
    private static boolean isBadRequest(Throwable e) {
        return e instanceof CYException cy
                && Integer.valueOf(40101).equals(cy.getErrorCode())
                && cy.getArgs().length > 0
                && cy.getArgs()[0] instanceof Integer status
                && status == 400;
    }
}
