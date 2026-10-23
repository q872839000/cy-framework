package com.cy.ai.provider.openai;

import com.cy.ai.model.RerankRequest;
import com.cy.ai.model.RerankResponse;
import com.cy.core.exception.CYException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rerank 链路 wire 级测试
 * <p>
 * 以 JDK 内建 HttpServer 起真实 HTTP 服务，验证：
 * <ul>
 *     <li>{@link OpenAiClient#rerank} 请求路径、认证头、SNAKE_CASE 序列化（top_n / relevance_score）</li>
 *     <li>{@link OpenAiCompatibleProvider#rerank} 默认模型补齐与 documentText 提取</li>
 *     <li>未配置 rerank 模型时抛 40907</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
class OpenAiCompatibleProviderRerankTest {

    private HttpServer server;
    private WebClient webClient;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/rerank", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"model\":\"bge-reranker-v2-m3\",\"results\":["
                    + "{\"index\":1,\"relevance_score\":0.93,\"document\":\"文档乙\"},"
                    + "{\"index\":0,\"relevance_score\":0.11,\"document\":{\"text\":\"文档甲\"}}"
                    + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
    void rerankClientUsesSnakeCaseWireAndAuthHeader() {
        RerankResponse response = OpenAiClient.rerank(webClient, "v1", "secret-key",
                RerankRequest.builder()
                        .model("bge-reranker-v2-m3")
                        .query("查询")
                        .documents(List.of("文档甲", "文档乙"))
                        .topN(2)
                        .build()).block();

        // 请求体必须是 snake_case（top_n 而非 topN）
        assertThat(capturedBody.get()).contains("\"top_n\":2");
        assertThat(capturedBody.get()).doesNotContain("topN");
        assertThat(capturedBody.get()).contains("\"model\":\"bge-reranker-v2-m3\"");
        assertThat(capturedAuth.get()).isEqualTo("Bearer secret-key");

        assertThat(response).isNotNull();
        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getIndex()).isEqualTo(1);
        assertThat(response.getResults().get(0).getRelevanceScore()).isEqualTo(0.93);
        // document 两种形态（纯文本 / {"text": ...}）都能提取
        assertThat(response.getResults().get(0).documentText()).isEqualTo("文档乙");
        assertThat(response.getResults().get(1).documentText()).isEqualTo("文档甲");
    }

    @Test
    void providerFillsDefaultRerankModel() {
        OpenAiCompatibleProvider provider = providerWithRerankModel("bge-default");

        RerankResponse response = provider.rerank(RerankRequest.builder()
                .query("查询")
                .documents(List.of("文档甲"))
                .build()).block();

        assertThat(response).isNotNull();
        assertThat(capturedBody.get()).contains("\"model\":\"bge-default\"");
    }

    @Test
    void providerWithoutRerankModelThrows40907() {
        OpenAiCompatibleProvider provider = providerWithRerankModel(null);

        assertThat(provider.supportsRerank()).isFalse();
        assertThatThrownBy(() -> provider.rerank(RerankRequest.builder()
                .query("查询").documents(List.of("文档甲")).build()).block())
                .isInstanceOf(CYException.class)
                .satisfies(e -> assertThat(((CYException) e).getErrorCode()).isEqualTo(40907));
    }

    /** 构造仅差异 rerankModel 的 provider（其余参数本测试无关）。 */
    private OpenAiCompatibleProvider providerWithRerankModel(String rerankModel) {
        return new OpenAiCompatibleProvider(
                "test", "http://127.0.0.1:" + server.getAddress().getPort(), "key",
                "v1", "chat-model",
                null, null, null, null,
                null, null, null,
                null, rerankModel,
                null, false, false,
                webClient);
    }
}
