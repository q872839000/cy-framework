package com.cy.ai.provider;

import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.EmbeddingRequest;
import com.cy.ai.model.EmbeddingResponse;
import com.cy.ai.thinking.ThinkingControlMode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI 模型服务 Provider 接口
 * <p>
 * 定义与模型厂商交互的统一契约。所有厂商实现此接口后注册到
 * {@link com.cy.ai.AiModelRegistry}，由 {@link com.cy.ai.AiFacade} 统一调度。
 * </p>
 * <p>
 * 当前接口仅包含 Chat Completions 能力。未来扩展 Embeddings、Image 等
 * 能力时，以 default 方法形式添加（抛出 {@code UnsupportedOperationException}），
 * 不破坏已有实现。
 * </p>
 * <p>
 * 内置实现：
 * {@link com.cy.ai.provider.openai.OpenAiCompatibleProvider}
 * —— 覆盖所有遵循 OpenAI 兼容协议的厂商。
 * </p>
 *
 * @author pcy
 * @date 2026/03/09
 */
public interface AiProvider {

    /**
     * 获取 Provider 唯一标识名
     * <p>
     * 对应配置文件 {@code cy.ai.models.<name>} 中的 key，
     * 同时也是 {@link com.cy.ai.AiModelRegistry} 中的查找键。
     * </p>
     *
     * @return 模型配置名称（如 "glm-4-air"、"glm-4-flash"）
     */
    String name();

    /**
     * 非流式对话补全
     * <p>
     * 发送对话请求并一次性返回完整响应。适用于需要完整结果后再处理的场景，
     * 如意图分类、JSON 提取、标题生成等。
     * </p>
     *
     * @param request 对话补全请求
     * @return 包含完整响应的 Mono
     */
    Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request);

    /**
     * 流式对话补全
     * <p>
     * 发送对话请求并以 SSE（Server-Sent Events）方式逐 chunk 返回响应。
     * 适用于需要实时展示生成过程的场景，如对话界面的逐字输出。
     * </p>
     *
     * @param request 对话补全请求
     * @return 包含多个响应块的 Flux
     */
    Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request);

    /**
     * Embedding 向量化
     * <p>
     * 默认抛 {@link UnsupportedOperationException}——不具备 embedding 能力的 provider
     * 无需实现；具备能力的 provider（如 OpenAiCompatibleProvider）覆盖此方法。
     * 调用方（如 AiFacade）应先经 {@link #supportsEmbeddings()} 探测。
     * </p>
     *
     * @param request Embedding 请求
     * @return 包含完整响应的 Mono
     */
    default Mono<EmbeddingResponse> embeddings(EmbeddingRequest request) {
        throw new UnsupportedOperationException(
                "Provider '" + name() + "' 不支持 Embeddings 能力");
    }

    /**
     * 是否具备 Embedding 能力（默认 false，具备能力的 provider 覆盖）。
     *
     * @return true 表示可调用 {@link #embeddings(EmbeddingRequest)}
     */
    default boolean supportsEmbeddings() {
        return false;
    }

    /**
     * 是否具备视觉（图片输入）能力（默认 false，具备能力的 provider 覆盖）。
     * <p>
     * true 表示 chat 模型接受 OpenAI 多模态 content parts（image_url）输入，
     * 调用方（编排层 / 前端）据此放行或拦截图片输入。
     * </p>
     *
     * @return true 表示 chat 请求可携带图片内容块
     */
    default boolean supportsVision() {
        return false;
    }

    /**
     * Rerank 重排
     * <p>
     * 默认抛 {@link UnsupportedOperationException}——不具备 rerank 能力的 provider
     * 无需实现；具备能力的 provider（如 OpenAiCompatibleProvider）覆盖此方法。
     * 调用方（如 AiFacade）应先经 {@link #supportsRerank()} 探测。
     * </p>
     *
     * @param request Rerank 请求
     * @return 包含重排结果的 Mono
     */
    default Mono<com.cy.ai.model.RerankResponse> rerank(com.cy.ai.model.RerankRequest request) {
        throw new UnsupportedOperationException(
                "Provider '" + name() + "' 不支持 Rerank 能力");
    }

    /**
     * 是否具备 Rerank 能力（默认 false，具备能力的 provider 覆盖）。
     *
     * @return true 表示可调用 {@link #rerank(com.cy.ai.model.RerankRequest)}
     */
    default boolean supportsRerank() {
        return false;
    }

    /**
     * 查询该 provider 的可用模型清单（OpenAI 兼容 {@code GET /v1/models}）
     * <p>
     * 默认抛 {@link UnsupportedOperationException}——不支持该协议的实现无需覆盖。
     * 用于管理面"拉取模型列表"与能力探测。
     * </p>
     *
     * @return 模型清单响应的 Mono
     */
    default Mono<com.cy.ai.model.ModelListResponse> listModels() {
        throw new UnsupportedOperationException(
                "Provider '" + name() + "' 不支持模型列表查询");
    }

    /**
     * 获取此 provider 的思考控制模式。
     * <p>
     * 默认返回 {@link ThinkingControlMode#NONE}（无思考控制能力）。
     * 各 provider 实现按构造时传入的配置返回，供上层（如 LlmGateway）通过
     * {@link com.cy.ai.thinking.ThinkingPolicyTranslator} 将厂商无关的思考策略
     * 翻译为具体 wire 参数。
     * </p>
     *
     * @return 思考控制模式
     */
    default ThinkingControlMode thinkingControlMode() {
        return ThinkingControlMode.NONE;
    }

    /**
     * 是否开启模型思考能力（provider 级全局开关）。
     * <p>
     * 默认返回 {@code true}。各 provider 实现按构造时传入的配置返回，供上层查询
     * 当前 provider 的全局思考开关状态（如日志观测、健康检查探针构造）。
     * </p>
     *
     * @return {@code true} 表示开启思考（跟随模型默认）；{@code false} 表示全局关闭
     */
    default boolean thinkingEnabled() {
        return true;
    }
}
