package com.cy.ai.provider.openai;

import com.cy.ai.model.ChatCompletionChunk;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.ChatMessage;
import com.cy.ai.model.ResponseFormats;
import com.cy.core.exception.CYException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * response_format 端点协商降级测试
 * <p>
 * 以 JDK 内建 HttpServer 模拟端点（携带 response_format 即 400，model=m500 即 500），验证：
 * <ul>
 *     <li>带 response_format 的请求遇 400 自动剥离该参数重试并成功（非流式与流式一致）</li>
 *     <li>协商结果按模型缓存：后续请求直接免探测降级，不再重复 400</li>
 *     <li>降级重试仍 400 则原样抛出；协商按模型隔离互不影响</li>
 *     <li>错误体未点名 response_format 的 400 只降级重试不沉淀缓存</li>
 *     <li>非 400 错误与不携带 response_format 的请求不触发降级重试</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/10/15
 */
class OpenAiCompatibleProviderResponseFormatTest {

    private HttpServer server;
    private WebClient webClient;
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBodies.add(requestBody);
            if (requestBody.contains("\"model\":\"m500\"")) {
                respondError(exchange, 500, "internal error");
                return;
            }
            if (requestBody.contains("\"model\":\"m400\"")) {
                respondError(exchange, 400, "response_format is not supported");
                return;
            }
            if (requestBody.contains("\"model\":\"mvague\"")) {
                respondError(exchange, 400, "maximum context length exceeded");
                return;
            }
            if (requestBody.contains("\"response_format\"")) {
                respondError(exchange, 400, "response_format is not supported");
                return;
            }
            boolean stream = requestBody.contains("\"stream\":true");
            byte[] body = (stream ? ssePayload() : jsonPayload()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        // 与 AiAutoConfiguration#aiObjectMapper 一致的 SNAKE_CASE 序列化（生产 wire 约定）
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        webClient = WebClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .codecs(codecs -> {
                    codecs.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
                    codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void syncDowngradesOn400AndCachesNegotiation() {
        OpenAiCompatibleProvider provider = newProvider();

        ChatCompletionResponse first = provider.chatCompletion(jsonRequest(null)).block();
        assertThat(first).isNotNull();
        assertThat(first.getFirstMessage().getContent()).isEqualTo("{\"ok\":true}");
        // 第一次 400（带 response_format），第二次剥离后成功
        assertThat(capturedBodies).hasSize(2);
        assertThat(capturedBodies.get(0)).contains("\"response_format\"");
        assertThat(capturedBodies.get(1)).doesNotContain("response_format");

        // 协商结果已缓存：同模型再次调用直接免探测降级，不再产生 400
        ChatCompletionResponse second = provider.chatCompletion(jsonRequest(null)).block();
        assertThat(second).isNotNull();
        assertThat(capturedBodies).hasSize(3);
        assertThat(capturedBodies.get(2)).doesNotContain("response_format");
    }

    @Test
    void streamDowngradesOn400() {
        OpenAiCompatibleProvider provider = newProvider();

        List<ChatCompletionChunk> chunks = provider.chatCompletionStream(jsonRequest(null))
                .collectList().block();

        assertThat(chunks).isNotEmpty();
        assertThat(capturedBodies).hasSize(2);
        assertThat(capturedBodies.get(0)).contains("\"response_format\"");
        assertThat(capturedBodies.get(1)).doesNotContain("response_format");
        assertThat(capturedBodies.get(1)).contains("\"stream\":true");
    }

    @Test
    void non400ErrorDoesNotDowngrade() {
        OpenAiCompatibleProvider provider = newProvider();

        assertThatThrownBy(() -> provider.chatCompletion(jsonRequest("m500")).block())
                .isInstanceOf(CYException.class);
        // 500 不触发降级重试，只发了一次
        assertThat(capturedBodies).hasSize(1);
    }

    @Test
    void requestWithoutFormatPassesThrough() {
        OpenAiCompatibleProvider provider = newProvider();

        ChatCompletionResponse response = provider.chatCompletion(ChatCompletionRequest.builder()
                .messages(List.of(ChatMessage.user("你好")))
                .build()).block();

        assertThat(response).isNotNull();
        assertThat(capturedBodies).hasSize(1);
        assertThat(capturedBodies.get(0)).doesNotContain("response_format");
    }

    @Test
    void retryStill400Propagates() {
        OpenAiCompatibleProvider provider = newProvider();

        assertThatThrownBy(() -> provider.chatCompletion(jsonRequest("m400")).block())
                .isInstanceOf(CYException.class);
        // 降级重试仍 400：原样抛出，只发两次
        assertThat(capturedBodies).hasSize(2);
        assertThat(capturedBodies.get(0)).contains("\"response_format\"");
        assertThat(capturedBodies.get(1)).doesNotContain("response_format");
    }

    @Test
    void negotiationIsPerModel() {
        OpenAiCompatibleProvider provider = newProvider();

        provider.chatCompletion(jsonRequest("m-a")).block();
        provider.chatCompletion(jsonRequest("m-b")).block();

        // m-a 的协商结果不影响 m-b 的首次探测
        assertThat(capturedBodies).hasSize(4);
        assertThat(capturedBodies.get(0)).contains("\"response_format\"");
        assertThat(capturedBodies.get(2)).contains("\"response_format\"");
    }

    @Test
    void vague400DowngradesWithoutCaching() {
        OpenAiCompatibleProvider provider = newProvider();

        assertThatThrownBy(() -> provider.chatCompletion(jsonRequest("mvague")).block())
                .isInstanceOf(CYException.class);
        assertThatThrownBy(() -> provider.chatCompletion(jsonRequest("mvague")).block())
                .isInstanceOf(CYException.class);

        // 错误体未点名 response_format：降级重试但不沉淀，下次调用仍带格式约束探测
        assertThat(capturedBodies).hasSize(4);
        assertThat(capturedBodies.get(0)).contains("\"response_format\"");
        assertThat(capturedBodies.get(2)).contains("\"response_format\"");
    }

    /** 向端点响应写入 JSON 错误体。 */
    private static void respondError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("{\"error\":{\"message\":\"" + message + "\",\"type\":\"invalid_request_error\"}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** 非流式 200 响应体。 */
    private static String jsonPayload() {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"chat-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},"
                + "\"finish_reason\":\"stop\"}]}";
    }

    /** 流式 200 响应体（SSE 两帧 + DONE）。 */
    private static String ssePayload() {
        return "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"chat-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"chat-model\",\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    /** 携带 response_format=json_object 的请求；model 为 null 时走 provider 默认模型。 */
    private static ChatCompletionRequest jsonRequest(String model) {
        return ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(ChatMessage.user("输出 JSON")))
                .responseFormat(ResponseFormats.jsonObject())
                .build();
    }

    /** 每测试独立 provider：协商缓存隔离。 */
    private OpenAiCompatibleProvider newProvider() {
        return new OpenAiCompatibleProvider(
                "test", "http://127.0.0.1:" + server.getAddress().getPort(), "key",
                "v1", "chat-model",
                null, null, null, null,
                null, null, null,
                null, null,
                null, false, false,
                webClient);
    }
}
