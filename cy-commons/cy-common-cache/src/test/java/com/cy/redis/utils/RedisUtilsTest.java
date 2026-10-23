package com.cy.redis.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisUtilsTest {

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(RedisUtils.class, "CLIENT", null);
    }

    @Test
    void shouldSetObjectWithDurationAtomically() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<Object> bucket = mock(RBucket.class);
        when(redissonClient.getBucket("cache:test")).thenReturn(bucket);
        ReflectionTestUtils.setField(RedisUtils.class, "CLIENT", redissonClient);

        Duration duration = Duration.ofMinutes(5);
        RedisUtils.setCacheObject("cache:test", "value", duration);

        verify(bucket).set("value", duration);
    }
}
