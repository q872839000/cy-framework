package com.cy.orrery.agent.runtime.identity;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint;
import com.cy.orrery.agent.runtime.config.OrreryAgentRuntimeProperties;
import com.cy.orrery.protocol.model.AgentDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentIdentityPublisher 单测：A2A 卡片+端点发布、重试、心跳续期、优雅注销、端点推导。
 */
class AgentIdentityPublisherTest {

    private AiService aiService;
    private Environment environment;
    private OrreryAgentRuntimeProperties.IdentitySpec spec;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        environment = mock(Environment.class);
        spec = new OrreryAgentRuntimeProperties.IdentitySpec();
        spec.setEnabled(true);
        spec.setHeartbeatIntervalSeconds(0);
    }

    private AgentIdentityPublisher publisher(AgentDescriptor... descriptors) {
        ObjectProvider<AgentDescriptor> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(inv -> Stream.of(descriptors));
        return new AgentIdentityPublisher(provider, spec, new ObjectMapper(), environment, aiService);
    }

    private AgentDescriptor descriptor(String agentId, String endpoint) {
        return AgentDescriptor.builder()
                .agentId(agentId)
                .name(agentId + "-name")
                .version("1.0.0")
                .serviceName(agentId)
                .publicEndpoint(endpoint)
                .enabled(true)
                .build();
    }

    @Test
    void publishesCardAndEndpointPerDescriptor() throws Exception {
        AgentIdentityPublisher p = publisher(
                descriptor("agent-a", "http://127.0.0.1:9091/"),
                descriptor("agent-b", "http://127.0.0.1:9092/"));
        p.publishAll();

        verify(aiService, times(2)).releaseAgentCard(any(AgentCard.class), eq("1.0.0"), eq(true));
        verify(aiService, times(1)).registerAgentEndpoint(eq("agent-a"), any(AgentEndpoint.class));
        verify(aiService, times(1)).registerAgentEndpoint(eq("agent-b"), any(AgentEndpoint.class));
    }

    @Test
    void publishRetriesWithBackoffAndHeartbeatCompensates() throws Exception {
        // 前两次失败第三次成功
        doThrow(new RuntimeException("e1")).doThrow(new RuntimeException("e2"))
                .doNothing().when(aiService)
                .releaseAgentCard(any(AgentCard.class), anyString(), anyBoolean());
        spec.setRetryIntervalMillis(1L);

        AgentIdentityPublisher p = publisher(descriptor("agent-a", "http://127.0.0.1:9091/"));
        p.publishAll();
        verify(aiService, times(3)).releaseAgentCard(any(AgentCard.class), anyString(), anyBoolean());

        // 心跳再补一次（幂等重发）
        p.heartbeatSafely();
        verify(aiService, times(4)).releaseAgentCard(any(AgentCard.class), eq("1.0.0"), eq(true));
    }

    @Test
    void publishRetryExhaustionStillRegisteredForHeartbeat() throws Exception {
        doThrow(new RuntimeException("always")).when(aiService)
                .releaseAgentCard(any(AgentCard.class), anyString(), anyBoolean());
        spec.setRetryIntervalMillis(1L);
        spec.setMaxRetries(1);

        AgentIdentityPublisher p = publisher(descriptor("agent-a", "http://127.0.0.1:9091/"));
        assertDoesNotThrow(p::publishAll);
        verify(aiService, times(2)).releaseAgentCard(any(AgentCard.class), anyString(), anyBoolean());

        // 恢复后心跳续期成功
        org.mockito.Mockito.doNothing().when(aiService)
                .releaseAgentCard(any(AgentCard.class), anyString(), anyBoolean());
        p.heartbeatSafely();
        verify(aiService, times(3)).releaseAgentCard(any(AgentCard.class), eq("1.0.0"), eq(true));
    }

    @Test
    void derivesEndpointFromServerPortWhenNoPublicEndpoint() throws Exception {
        when(environment.getProperty("server.port")).thenReturn("9099");
        when(environment.getProperty("spring.cloud.nacos.discovery.ip")).thenReturn("10.0.0.9");
        AgentIdentityPublisher p = publisher(descriptor("agent-internal", null));
        p.publishAll();

        // 无 publicEndpoint 也注册端点（推导 host:port）——A2A 是唯一通道
        verify(aiService, times(1)).releaseAgentCard(any(AgentCard.class), eq("1.0.0"), eq(true));
        verify(aiService, times(1)).registerAgentEndpoint(eq("agent-internal"), any(AgentEndpoint.class));
    }

    @Test
    void destroyDeregistersEndpointAndMarksCardNotLatestWhenGraceful() throws Exception {
        AgentIdentityPublisher p = publisher(descriptor("agent-a", "http://127.0.0.1:9091/"));
        p.publishAll();
        p.destroy();

        verify(aiService, times(1)).deregisterAgentEndpoint(eq("agent-a"), any(AgentEndpoint.class));
        // 优雅注销：卡片再发一次 setLatest=false
        verify(aiService, times(1)).releaseAgentCard(any(AgentCard.class), eq("1.0.0"), eq(false));
    }

    @Test
    void publishAllWithNoDescriptorsIsNoop() {
        AgentIdentityPublisher p = publisher();
        assertDoesNotThrow(p::publishAll);
        org.mockito.Mockito.verifyNoInteractions(aiService);
    }
}
