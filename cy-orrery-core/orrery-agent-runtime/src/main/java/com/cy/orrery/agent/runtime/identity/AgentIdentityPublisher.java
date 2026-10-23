// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.identity;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.cy.orrery.agent.runtime.config.NacosClientSupport;
import com.cy.orrery.agent.runtime.config.OrreryAgentRuntimeProperties;
import com.cy.orrery.protocol.model.AgentDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * Agent 身份发布器（Nacos 原生 A2A 通道）。
 * <p>
 * 宿主声明一个或多个 {@link AgentDescriptor} Bean（单进程多 Agent 时声明多个），
 * 进程就绪后逐一发布到 Nacos A2A：
 * </p>
 * <ol>
 *   <li><b>卡片</b>：{@code releaseAgentCard}（setLatest=true），完整描述符经
 *       卡片 extension（{@code orrery://catalog}）携带富语义副本；</li>
 *   <li><b>端点</b>：{@code registerAgentEndpoint}，承载活性与调用地址。</li>
 * </ol>
 * <p>
 * <b>租约与心跳</b>：A2A 端点是租约模型——续期=在线、不续期=过期下线。
 * 本发布器按 {@code heartbeat-interval-seconds} 周期幂等重发卡片+端点保活；
 * 进程崩溃（不再续期）后端点被 Nacos 过期清除，实现崩溃自动下线。
 * 优雅停机（{@code graceful-deregister=true}）时主动摘端点并把卡片置为非最新。
 * </p>
 *
 * @author pcy
 * @since 2026/09/03
 */
public class AgentIdentityPublisher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentityPublisher.class);

    /** 缺省服务端口（server.port 未配置时推导端点用）。 */
    private static final String DEFAULT_SERVER_PORT = "8080";

    /** 主机名解析失败时的回环地址兜底。 */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    /** 端点传输协议（A2A JSON-RPC over HTTP）。 */
    private static final String ENDPOINT_TRANSPORT = "JSONRPC";

    /** 端点协议。 */
    private static final String ENDPOINT_PROTOCOL = "HTTP";

    /** 端点 URL 协议头。 */
    private static final String ENDPOINT_SCHEME = "http://";

    private final ObjectProvider<AgentDescriptor> descriptorProvider;
    private final OrreryAgentRuntimeProperties.IdentitySpec spec;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final AiService aiService;

    /** agentId → 描述符（心跳续期与优雅注销的基准）。 */
    private final Map<String, AgentDescriptor> published = new ConcurrentHashMap<>();

    /** 心跳调度线程（daemon 单线程，周期幂等重发）。 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-identity-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造身份发布器（AiService 按 identity 覆盖 + spring.cloud.nacos.* 共享配置自建）。
     *
     * @param descriptorProvider 宿主声明的 AgentDescriptor（0..N 个）
     * @param properties         运行时配置
     * @param objectMapper       JSON 编解码器
     * @param environment        环境
     */
    public AgentIdentityPublisher(ObjectProvider<AgentDescriptor> descriptorProvider,
                                  OrreryAgentRuntimeProperties properties,
                                  ObjectMapper objectMapper,
                                  Environment environment) {
        this(descriptorProvider, properties.getIdentity(), objectMapper, environment,
                buildClient(environment, properties.getIdentity()));
    }

    /** 测试/定制装配缝：直接注入 AiService。 */
    AgentIdentityPublisher(ObjectProvider<AgentDescriptor> descriptorProvider,
                           OrreryAgentRuntimeProperties.IdentitySpec spec,
                           ObjectMapper objectMapper,
                           Environment environment,
                           AiService aiService) {
        this.descriptorProvider = descriptorProvider;
        this.spec = spec;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.aiService = aiService;
    }

    /** 构建 Nacos AiService 客户端（失败即启动失败——identity 开启即强依赖 Nacos）。 */
    private static AiService buildClient(Environment environment,
                                         OrreryAgentRuntimeProperties.IdentitySpec spec) {
        try {
            return AiFactory.createAiService(resolveNacosProperties(environment, spec));
        } catch (Exception e) {
            throw new IllegalStateException("Nacos AiService 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 进程就绪后发布全部 Agent 身份，并启动心跳续期。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void publishAll() {
        List<AgentDescriptor> descriptors = descriptorProvider.stream().toList();
        if (descriptors.isEmpty()) {
            log.warn("identity 已启用但未发现 AgentDescriptor Bean，身份发布跳过");
            return;
        }
        for (AgentDescriptor descriptor : descriptors) {
            publishOne(descriptor);
        }
        long interval = spec.getHeartbeatIntervalSeconds();
        if (interval > 0) {
            scheduler.scheduleWithFixedDelay(this::heartbeatSafely, interval, interval, TimeUnit.SECONDS);
        }
        log.info("Agent 身份发布完成: count={}, agents={}, 心跳间隔={}s",
                descriptors.size(), descriptors.stream().map(AgentDescriptor::getAgentId).toList(), interval);
    }

    /** 发布单个 Agent：卡片 + 端点（重试，指数退避）。 */
    private void publishOne(AgentDescriptor descriptor) {
        String agentId = descriptor.getAgentId();
        // A2A 是唯一通道，端点必须存在：无 publicEndpoint 时回填推导值，
        // 让卡片（url）与端点注册用同一地址，保证卡片满足 A2A 1.0.0 校验
        String endpoint = descriptor.getPublicEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            descriptor.setPublicEndpoint(resolveEndpoint(descriptor));
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(descriptor);
        } catch (Exception e) {
            log.error("AgentDescriptor 序列化失败，放弃发布: agentId={}", agentId, e);
            return;
        }
        long interval = spec.getRetryIntervalMillis();
        for (int attempt = 0; attempt <= spec.getMaxRetries(); attempt++) {
            try {
                aiService.releaseAgentCard(
                        NacosAgentCardMapper.toNacosCard(descriptor, json), descriptor.getVersion(), true);
                registerEndpoint(descriptor);
                published.put(agentId, descriptor);
                log.info("Agent 身份发布成功: agentId={}, version={}, endpoint={}",
                        agentId, descriptor.getVersion(), descriptor.getPublicEndpoint());
                return;
            } catch (Exception e) {
                log.warn("Agent 身份发布失败（第 {} 次）: agentId={}, err={}", attempt + 1, agentId, e.getMessage());
                if (attempt < spec.getMaxRetries()) {
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    interval *= 2;
                }
            }
        }
        log.error("Agent 身份发布重试耗尽: agentId={}，将由心跳持续兜底", agentId);
        // 即便本次失败也登记，心跳周期会反复重试直至成功
        published.put(agentId, descriptor);
    }

    /** 端点注册：host/port 取自 publicEndpoint（publishOne 已保证非空）。 */
    private void registerEndpoint(AgentDescriptor descriptor) throws Exception {
        aiService.registerAgentEndpoint(descriptor.getAgentId(), toAgentEndpoint(descriptor));
    }

    /** 构造 A2A 端点对象（host/port 取自 publicEndpoint，发布与注销共用）。 */
    private AgentEndpoint toAgentEndpoint(AgentDescriptor descriptor) {
        URI uri = URI.create(descriptor.getPublicEndpoint());
        AgentEndpoint endpoint = new AgentEndpoint();
        endpoint.setAddress(uri.getHost());
        endpoint.setPort(uri.getPort());
        endpoint.setVersion(descriptor.getVersion());
        endpoint.setTransport(ENDPOINT_TRANSPORT);
        endpoint.setProtocol(ENDPOINT_PROTOCOL);
        return endpoint;
    }

    /** 解析公开端点：优先 publicEndpoint，缺省推导 http://{host}:{server.port}。 */
    private String resolveEndpoint(AgentDescriptor descriptor) {
        String endpoint = descriptor.getPublicEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        String port = environment == null ? null : environment.getProperty("server.port");
        if (port == null || port.isBlank()) {
            port = DEFAULT_SERVER_PORT;
        }
        String host = environment == null ? null
                : environment.getProperty("spring.cloud.nacos.discovery.ip");
        if (host == null || host.isBlank()) {
            host = resolveLocalHost();
        }
        return ENDPOINT_SCHEME + host + ":" + port;
    }

    /** 本机地址解析（失败回落回环地址并告警——端点推导降级而非启动失败）。 */
    private static String resolveLocalHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            log.warn("本机地址解析失败，端点推导回落 {}: {}", LOOPBACK_HOST, e.getMessage());
            return LOOPBACK_HOST;
        }
    }

    /** 心跳续期：周期幂等重发卡片+端点（兼作漂移对账），包可见供单测直接驱动。 */
    void heartbeatSafely() {
        try {
            for (AgentDescriptor descriptor : published.values()) {
                try {
                    String json = objectMapper.writeValueAsString(descriptor);
                    aiService.releaseAgentCard(
                            NacosAgentCardMapper.toNacosCard(descriptor, json), descriptor.getVersion(), true);
                    registerEndpoint(descriptor);
                } catch (Exception e) {
                    log.warn("身份心跳续期失败: agentId={}, err={}", descriptor.getAgentId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("身份心跳周期任务异常: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        if (!spec.isGracefulDeregister()) {
            return;
        }
        for (AgentDescriptor descriptor : published.values()) {
            String agentId = descriptor.getAgentId();
            try {
                aiService.deregisterAgentEndpoint(agentId, toAgentEndpoint(descriptor));
                // 卡片置为非最新（保留下线卡片，不再作为可用版本）
                String json = objectMapper.writeValueAsString(descriptor);
                aiService.releaseAgentCard(
                        NacosAgentCardMapper.toNacosCard(descriptor, json), descriptor.getVersion(), false);
                log.info("Agent 身份优雅注销: agentId={}", agentId);
            } catch (Exception e) {
                log.warn("Agent 身份注销失败: agentId={}, err={}", agentId, e.getMessage());
            }
        }
    }

    /** 解析 Nacos 连接参数：identity 覆盖优先，缺省回落 spring.cloud.nacos.* 共享配置。 */
    private static Properties resolveNacosProperties(Environment environment,
                                                               OrreryAgentRuntimeProperties.IdentitySpec spec) {
        return NacosClientSupport.toClientProperties(
                NacosClientSupport.firstNonBlank(spec.getServerAddr(),
                        environment.getProperty("spring.cloud.nacos.server-addr")),
                NacosClientSupport.firstNonBlank(spec.getUsername(),
                        environment.getProperty("spring.cloud.nacos.username")),
                NacosClientSupport.firstNonBlank(spec.getPassword(),
                        environment.getProperty("spring.cloud.nacos.password")),
                NacosClientSupport.firstNonBlank(spec.getNamespace(),
                        environment.getProperty("spring.cloud.nacos.discovery.namespace")));
    }
}
