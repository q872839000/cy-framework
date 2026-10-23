package com.cy.ai.config;

import com.cy.ai.provider.AiProvider;
import com.cy.ai.provider.openai.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.Arrays;

/**
 * AI Provider 装配工厂
 * <p>
 * 将 provider 配置（{@link AiProperties.ProviderConfig}）构建为运行态实例：
 * 每个 provider 拥有独立的 {@link WebClient} 与连接池，互不影响。
 * 构建逻辑集中在此，供启动装配（{@link AiAutoConfiguration}）与
 * 运行期热刷新（{@link AiProviderRegistry}）复用同一路径。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
public class AiProviderFactory {

    /** AI 模块专用 ObjectMapper（SNAKE_CASE wire 序列化） */
    private final ObjectMapper aiObjectMapper;

    public AiProviderFactory(ObjectMapper aiObjectMapper) {
        this.aiObjectMapper = aiObjectMapper;
    }

    /**
     * Provider 与其专属连接池的组合体
     * <p>
     * 连接池生命周期归 {@link AiProviderRegistry} 统一管理：provider 被移除或配置变更
     * 重建时，旧连接池随之释放。
     * </p>
     */
    public record ProviderHandle(AiProvider provider, ConnectionProvider connectionProvider) {

        /** 异步释放连接池（不阻塞调用线程，在途连接随池中连接关闭后回收）。 */
        public void closeQuietly() {
            connectionProvider.disposeLater().subscribe();
        }
    }

    /**
     * 按配置构建 provider 实例及其连接池
     *
     * @param name   provider 名称（cy.ai.providers 的 key）
     * @param config provider 配置
     * @return provider 与连接池的组合体
     */
    public ProviderHandle create(String name, AiProperties.ProviderConfig config) {
        // 连接池单实例贯穿 WebClient 与返回句柄——句柄释放时必须管得到实际在用的池
        ConnectionProvider connectionProvider = buildConnectionProvider(config);
        WebClient webClient = buildWebClient(config, connectionProvider);
        AiProperties.ChatOptions options = config.getChat().getOptions();

        // embedding / rerank 模型：enabled 且配置了模型名才启用对应能力
        String embeddingModel = config.getEmbedding() != null
                && config.getEmbedding().isEnabled()
                && config.getEmbedding().getOptions() != null
                ? config.getEmbedding().getOptions().getModel() : null;
        String rerankModel = config.getRerank() != null
                && config.getRerank().isEnabled()
                && config.getRerank().getOptions() != null
                ? config.getRerank().getOptions().getModel() : null;

        // 创建 OpenAI 兼容 Provider（目前所有厂商都兼容 OpenAI 协议）
        // 标准参数作为强类型默认值传入，厂商私有参数通过 extraParams 透传。
        AiProvider provider = new OpenAiCompatibleProvider(
                name,
                config.getBaseUrl(),
                config.getApiKey(),
                config.getApiVersion(),
                options.getModel(),
                options.getMaxTokens(),
                options.getTemperature(),
                options.getTopP(),
                options.getReasoningEffort(),
                options.getStop() != null ? Arrays.asList(options.getStop()) : null,
                options.getStreamOptions(),
                options.getExtraParams(),
                embeddingModel,
                rerankModel,
                options.getThinkingControlMode(),
                options.isThinkingEnabled(),
                config.getChat().isVisionEnabled(),
                webClient
        );

        return new ProviderHandle(provider, connectionProvider);
    }

    /**
     * 为 provider 配置构建独立的 WebClient
     *
     * @param config             provider 配置
     * @param connectionProvider 该 provider 的专属连接池（与句柄共享同一实例）
     */
    private WebClient buildWebClient(AiProperties.ProviderConfig config,
                                     ConnectionProvider connectionProvider) {
        String baseUrl = config.getBaseUrl().replaceAll("/+$", "");

        // 配置 Netty HTTP 客户端（连接超时 + 响应超时）
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) config.getConnectTimeout().toMillis())
                .responseTimeout(config.getReadTimeout());

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", "application/json")
                .codecs(codecConfigurer -> {
                    // 设置响应体最大缓冲 16MB（大模型响应可能较长）
                    codecConfigurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
                    codecConfigurer.defaultCodecs().jackson2JsonDecoder(
                            new Jackson2JsonDecoder(aiObjectMapper));
                    codecConfigurer.defaultCodecs().jackson2JsonEncoder(
                            new Jackson2JsonEncoder(aiObjectMapper));
                })
                .build();
    }

    /** 构建 provider 专属连接池（独立池化，互不影响）。 */
    private ConnectionProvider buildConnectionProvider(AiProperties.ProviderConfig config) {
        String baseUrl = config.getBaseUrl().replaceAll("/+$", "");
        return ConnectionProvider.builder(baseUrl)
                .maxConnections(config.getMaxConnections())
                .pendingAcquireMaxCount(500)
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .maxIdleTime(Duration.ofMinutes(5))
                .build();
    }
}
