package com.cy.mq.runtime;

import com.cy.mq.core.MqMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class MqTraceSupportTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldClearParentSpanIdWhenIncomingMessageHasNoParentSpan() {
        MDC.put("traceId", "old-trace");
        MDC.put("spanId", "old-span");
        MDC.put("parentSpanId", "old-parent");

        MqMetadata metadata = MqMetadata.builder()
                .traceId("new-trace")
                .build();

        try (MqTraceSupport.TraceScope ignored = MqTraceSupport.establish(metadata)) {
            assertThat(MDC.get("traceId")).isEqualTo("new-trace");
            assertThat(MDC.get("spanId")).isNotBlank();
            assertThat(MDC.get("parentSpanId")).isNull();
        }

        assertThat(MDC.get("traceId")).isEqualTo("old-trace");
        assertThat(MDC.get("spanId")).isEqualTo("old-span");
        assertThat(MDC.get("parentSpanId")).isEqualTo("old-parent");
    }

    @Test
    void shouldGenerateTraceIdForExternalMessage() {
        MqMetadata metadata = MqMetadata.builder()
                .synthetic(true)
                .build();

        try (MqTraceSupport.TraceScope ignored = MqTraceSupport.establish(metadata)) {
            assertThat(metadata.getTraceId()).isNotBlank();
            assertThat(MDC.get("traceId")).isEqualTo(metadata.getTraceId());
            assertThat(MDC.get("spanId")).isNotBlank();
            assertThat(MDC.get("parentSpanId")).isNull();
        }

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
        assertThat(MDC.get("parentSpanId")).isNull();
    }
}
