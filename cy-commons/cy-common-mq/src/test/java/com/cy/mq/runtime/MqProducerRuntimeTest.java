package com.cy.mq.runtime;

import com.cy.mq.codec.DefaultMqMessageCodec;
import com.cy.mq.codec.Fastjson2PayloadCodec;
import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqOutboundRecord;
import com.cy.mq.core.MqPublishRequest;
import com.cy.mq.core.MqRecordHandler;
import com.cy.mq.core.MqSubscribeSpec;
import com.cy.mq.core.MqSubscription;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MqProducerRuntimeTest {

    @Test
    void shouldSetResolvedDriverOnOutboundRecord() {
        MqProperties properties = new MqProperties();
        properties.setDriver(MqDriverType.LOCAL);
        CapturingDriver driver = new CapturingDriver();
        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(List.of(driver));

        MqProducerRuntime runtime = new MqProducerRuntime(
                registry,
                new DefaultMqMessageCodec(new Fastjson2PayloadCodec())
        );

        runtime.publish(MqPublishRequest.builder()
                .topic("runtime.topic")
                .payload("hello")
                .build());

        assertThat(driver.lastRecord.get()).isNotNull();
        assertThat(driver.lastRecord.get().getDriver()).isEqualTo(MqDriverType.LOCAL);
    }

    private static class CapturingDriver implements MqDriver {

        private final AtomicReference<MqOutboundRecord> lastRecord = new AtomicReference<>();

        @Override
        public MqDriverType type() {
            return MqDriverType.LOCAL;
        }

        @Override
        public void publish(MqOutboundRecord record) {
            lastRecord.set(record);
        }

        @Override
        public MqSubscription subscribe(MqSubscribeSpec spec, MqRecordHandler handler) {
            return () -> {
            };
        }
    }
}
