package com.cy.ai.provider.openai;

import com.cy.core.exception.CYException;
import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.EmbeddingRequest;
import com.cy.ai.model.EmbeddingResponse;
import com.cy.ai.model.ModelListResponse;
import com.cy.ai.model.RerankRequest;
import com.cy.ai.model.RerankResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容协议 HTTP 客户端（静态工具类）
 * <p>
 * 纯传输层实现，负责：
 * <ol>
 *     <li>构造 HTTP 请求（URI、认证头、请求体序列化）</li>
 *     <li>发送请求到指定的模型服务端点</li>
 *     <li>非流式模式：将响应体反序列化为 {@link ChatCompletionResponse}</li>
 *     <li>流式模式：利用 Spring WebFlux 内置的 {@code ServerSentEventHttpMessageReader}
 *         自动解析 SSE 协议，逐事件产出 {@link ChatCompletionChunk}</li>
 *     <li>统一处理 HTTP 错误码，转换为 {@link CYException}</li>
 * </ol>
 * </p>
 * <p>
 * 流式模式采用 {@code bodyToFlux(String.class)} 方式接收 SSE 事件，框架层面自动处理：
 * <ul>
 *     <li>UTF-8 多字节字符的跨 TCP 包边界问题（{@code StringDecoder} 维护解码器状态）</li>
 *     <li>SSE 协议解析（{@code data:} 前缀提取、空行事件分隔、注释行跳过）</li>
 * </ul>
 * 此方式兼容所有 OpenAI 协议厂商（OpenAI、DeepSeek、Kimi、GLM、Claude 等）。
 * </p>
 * <p>
 * <b>重构说明：</b>改为静态方法，所有状态通过参数传入，支持无状态的协议适配器设计。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    /** SSE 流结束标记（OpenAI 规范，所有兼容厂商通用） */
    private static final String SSE_DONE_MARKER = "[DONE]";

    /**
     * SSE 数据块反序列化专用 ObjectMapper
     * <p>
     * 配置与 {@code AiAutoConfiguration#aiObjectMapper()} 保持一致：
     * SNAKE_CASE 命名策略、忽略未知字段、忽略 null 字段。
     * </p>
     */
    private static final ObjectMapper CHUNK_MAPPER;

    static {
        CHUNK_MAPPER = new ObjectMapper();
        CHUNK_MAPPER.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CHUNK_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        CHUNK_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 私有构造函数，防止实例化
     */
    private OpenAiClient() {
    }

    /**
     * 非流式对话补全请求
     * <p>
     * 发送 POST 请求到 Chat Completions 端点，等待服务端一次性返回完整响应。
     * </p>
     *
     * @param webClient  已配置 baseUrl 的 WebClient 实例
     * @param apiVersion API 版本路径前缀（如 "v1"）
     * @param apiKey     API 认证密钥，为 null 或空白时不发送 Authorization 头
     * @param request    对话补全请求（stream / streamOptions 字段会被强制修正为非流式形态）
     * @return 包含完整响应的 Mono
     * @throws CYException 服务端返回 HTTP 错误时抛出
     */
    public static Mono<ChatCompletionResponse> chatCompletion(
            WebClient webClient,
            String apiVersion,
            String apiKey,
            ChatCompletionRequest request) {

        // 强制非流式模式
        request.setStream(false);
        // 规范约束：stream_options 仅在 stream=true 时合法——OpenAI / vLLM 对
        // stream=false + stream_options 的组合直接返回 400，非流式出口必须剔除
        request.setStreamOptions(null);

        String path = "/" + apiVersion + "/chat/completions";
        log.debug("发起非流式请求 -> {}, model={}", path, request.getModel());

        return webClient.post()
                .uri(path)
                .headers(headers -> applyAuth(headers, apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, OpenAiClient::handleErrorResponse)
                .bodyToMono(ChatCompletionResponse.class)
                .doOnError(e -> {
                    if (!(e instanceof CYException)) {
                        log.error("非流式请求异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * 流式对话补全请求
     * <p>
     * 发送 POST 请求到 Chat Completions 端点，以 SSE 方式接收数据流。
     * 利用 Spring WebFlux 内置的 {@code ServerSentEventHttpMessageReader}
     * 自动解析 SSE 协议格式，每个 SSE 事件的 {@code data} 字段作为独立的 String 元素发射。
     * </p>
     * <p>
     * 框架自动处理：
     * <ul>
     *     <li>SSE 协议解析（{@code data:} 前缀提取、空行事件分隔）</li>
     *     <li>UTF-8 多字节字符跨 TCP 包边界的完整性（{@code StringDecoder} 内部维护解码器状态）</li>
     * </ul>
     * 此方式兼容所有 OpenAI 协议厂商的 SSE 输出格式。
     * </p>
     *
     * @param webClient  已配置 baseUrl 的 WebClient 实例
     * @param apiVersion API 版本路径前缀（如 "v1"）
     * @param apiKey     API 认证密钥，为 null 或空白时不发送 Authorization 头
     * @param request    对话补全请求（stream 字段应为 true）
     * @return 包含多个响应块的 Flux
     * @throws CYException 服务端返回 HTTP 错误时抛出
     */
    public static Flux<ChatCompletionChunk> chatCompletionStream(
            WebClient webClient,
            String apiVersion,
            String apiKey,
            ChatCompletionRequest request) {

        // 强制流式模式
        request.setStream(true);

        String path = "/" + apiVersion + "/chat/completions";
        log.debug("发起流式请求 -> {}, model={}", path, request.getModel());

        return webClient.post()
                .uri(path)
                .headers(headers -> applyAuth(headers, apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, OpenAiClient::handleErrorResponse)
                .bodyToFlux(String.class)
                .filter(data -> data != null && !data.isBlank()
                        && !SSE_DONE_MARKER.equals(data.trim()))
                .mapNotNull(OpenAiClient::deserializeChunk)
                .doOnError(e -> {
                    if (!(e instanceof CYException)) {
                        log.error("流式请求异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * Embedding 向量化请求
     * <p>
     * 发送 POST 请求到 Embeddings 端点（OpenAI 兼容协议 {@code /v1/embeddings}），
     * 覆盖 bge-m3、text-embedding-3 系列、通义 text-embedding 等兼容厂商。
     * </p>
     *
     * @param webClient  已配置 baseUrl 的 WebClient 实例
     * @param apiVersion API 版本路径前缀（如 "v1"）
     * @param apiKey     API 认证密钥，为 null 或空白时不发送 Authorization 头
     * @param request    Embedding 请求
     * @return 包含完整响应的 Mono
     * @throws CYException 服务端返回 HTTP 错误时抛出
     */
    public static Mono<EmbeddingResponse> embeddings(
            WebClient webClient,
            String apiVersion,
            String apiKey,
            EmbeddingRequest request) {

        String path = "/" + apiVersion + "/embeddings";
        log.debug("发起 Embedding 请求 -> {}, model={}, inputCount={}", path, request.getModel(),
                request.getInput() == null ? 0 : request.getInput().size());

        return webClient.post()
                .uri(path)
                .headers(headers -> applyAuth(headers, apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, OpenAiClient::handleErrorResponse)
                .bodyToMono(EmbeddingResponse.class)
                .doOnError(e -> {
                    if (!(e instanceof CYException)) {
                        log.error("Embedding 请求异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * Rerank 重排请求
     * <p>
     * 发送 POST 请求到 Rerank 端点（{@code /v1/rerank}），
     * 覆盖 Jina / vLLM / SiliconFlow 等兼容厂商的重排模型。
     * </p>
     *
     * @param webClient  已配置 baseUrl 的 WebClient 实例
     * @param apiVersion API 版本路径前缀（如 "v1"）
     * @param apiKey     API 认证密钥，为 null 或空白时不发送 Authorization 头
     * @param request    Rerank 请求
     * @return 包含重排结果的 Mono
     * @throws CYException 服务端返回 HTTP 错误时抛出
     */
    public static Mono<RerankResponse> rerank(
            WebClient webClient,
            String apiVersion,
            String apiKey,
            RerankRequest request) {

        String path = "/" + apiVersion + "/rerank";
        log.debug("发起 Rerank 请求 -> {}, model={}, docCount={}", path, request.getModel(),
                request.getDocuments() == null ? 0 : request.getDocuments().size());

        return webClient.post()
                .uri(path)
                .headers(headers -> applyAuth(headers, apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, OpenAiClient::handleErrorResponse)
                .bodyToMono(RerankResponse.class)
                .doOnError(e -> {
                    if (!(e instanceof CYException)) {
                        log.error("Rerank 请求异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * 模型列表查询
     * <p>
     * 发送 GET 请求到 Models 端点（OpenAI 兼容协议 {@code /v1/models}），
     * 用于管理面"拉取模型列表"与连通性探测。
     * </p>
     *
     * @param webClient  已配置 baseUrl 的 WebClient 实例
     * @param apiVersion API 版本路径前缀（如 "v1"）
     * @param apiKey     API 认证密钥，为 null 或空白时不发送 Authorization 头
     * @return 包含模型清单的 Mono
     * @throws CYException 服务端返回 HTTP 错误时抛出
     */
    public static Mono<ModelListResponse> listModels(
            WebClient webClient,
            String apiVersion,
            String apiKey) {

        String path = "/" + apiVersion + "/models";
        log.debug("发起模型列表查询 -> {}", path);

        return webClient.get()
                .uri(path)
                .headers(headers -> applyAuth(headers, apiKey))
                .retrieve()
                .onStatus(HttpStatusCode::isError, OpenAiClient::handleErrorResponse)
                .bodyToMono(ModelListResponse.class)
                .doOnError(e -> {
                    if (!(e instanceof CYException)) {
                        log.error("模型列表查询异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * 设置 API 认证头
     * <p>
     * 当 apiKey 非空时，添加 "Authorization: Bearer {apiKey}" 头。
     * 本地模型服务通常无需认证，此时不添加该头。
     * </p>
     *
     * @param headers HTTP 请求头
     * @param apiKey  API 认证密钥
     */
    private static void applyAuth(org.springframework.http.HttpHeaders headers, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
    }

    /**
     * 将 SSE data 字段中的 JSON 字符串反序列化为 {@link ChatCompletionChunk}
     * <p>
     * 使用静态 {@link #CHUNK_MAPPER}（SNAKE_CASE + 忽略未知字段）。
     * 反序列化失败时记录警告日志并返回 null（被上层 {@code mapNotNull} 过滤）。
     * </p>
     *
     * @param json SSE data 字段中的 JSON 字符串
     * @return 解析成功返回 Chunk 对象，失败返回 null
     */
    private static ChatCompletionChunk deserializeChunk(String json) {
        try {
            return CHUNK_MAPPER.readValue(json, ChatCompletionChunk.class);
        } catch (Exception e) {
            log.warn("SSE 数据块 JSON 反序列化失败: {}", json, e);
            return null;
        }
    }

    /** 透传进异常消息的厂商错误体最大长度：足够容纳常见 JSON 错误详情，又不让异常消息失控。 */
    private static final int ERROR_BODY_SNIPPET_MAX = 300;

    /**
     * 处理 HTTP 错误响应
     * <p>
     * 读取响应体内容，封装为 {@link CYException} 抛出。响应体（厂商返回的 JSON 错误详情，
     * 如参数被拒的具体原因）截断后随异常消息透传给调用方——只写日志不进异常会让上游只看到
     * "HTTP 400"，无从判断是哪个参数、被哪条厂商规则拒绝。
     * </p>
     *
     * @param response 错误响应对象
     * @return 包含异常的 Mono
     */
    private static Mono<? extends Throwable> handleErrorResponse(ClientResponse response) {
        int statusCode = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    log.error("API 响应错误: HTTP {}, body={}", statusCode, body);
                    return (Throwable) new CYException(40101, statusCode, errorBodySnippet(body));
                });
    }

    /** 厂商错误体压缩为单行片段：去换行、截断超长，空体给占位文案。 */
    private static String errorBodySnippet(String body) {
        if (body == null || body.isBlank()) {
            return "(无响应体)";
        }
        String flat = body.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
        return flat.length() <= ERROR_BODY_SNIPPET_MAX
                ? flat
                : flat.substring(0, ERROR_BODY_SNIPPET_MAX) + "…";
    }
}
