package com.cy.redis.manager;

import com.cy.redis.config.properties.LocalCacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaffeineCacheDecoratorTest {

    @Test
    void shouldKeepLocalCacheConsistentAfterPutAndClear() {
        ConcurrentMapCache delegate = new ConcurrentMapCache("demo", new ConcurrentHashMap<>(), true);
        CaffeineCacheDecorator decorator = new CaffeineCacheDecorator(delegate, buildProperties());

        decorator.put("user:1", "alice");
        delegate.evict("user:1");

        assertEquals("alice", decorator.get("user:1").get());

        decorator.clear();
        delegate.put("user:1", "bob");

        assertEquals("bob", decorator.get("user:1").get());
    }

    @Test
    void shouldPopulateLocalCacheWhenPutIfAbsentSucceeds() {
        ConcurrentMapCache delegate = new ConcurrentMapCache("demo", new ConcurrentHashMap<>(), true);
        CaffeineCacheDecorator decorator = new CaffeineCacheDecorator(delegate, buildProperties());

        assertNull(decorator.putIfAbsent("user:2", "tom"));
        delegate.evict("user:2");

        assertEquals("tom", decorator.get("user:2").get());
    }

    private LocalCacheProperties buildProperties() {
        LocalCacheProperties properties = new LocalCacheProperties();
        properties.setEnabled(true);
        properties.setExpireAfterWrite(Duration.ofMinutes(1));
        properties.setInitialCapacity(16);
        properties.setMaximumSize(128);
        return properties;
    }
}
