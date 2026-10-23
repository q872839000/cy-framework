package com.cy.redis.aspect;

import com.cy.redis.annotation.CacheMapPut;
import com.cy.redis.utils.RedisUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CacheAspectTransactionTest {

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(RedisUtils.class, "CLIENT", null);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void shouldDeferCacheMapPutUntilTransactionCommitted() throws Throwable {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<Object, Object> rMap = mock(RMap.class);
        when(redissonClient.getMap("system:users")).thenReturn(rMap);
        ReflectionTestUtils.setField(RedisUtils.class, "CLIENT", redissonClient);

        CacheAspect cacheAspect = new CacheAspect();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = DemoService.class.getMethod("updateUser", DemoUser.class);
        DemoUser user = new DemoUser(1L, "alice");

        when(joinPoint.proceed()).thenReturn(Boolean.TRUE);
        when(joinPoint.getArgs()).thenReturn(new Object[]{user});
        when(joinPoint.getTarget()).thenReturn(new DemoService());
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);

        CacheMapPut cacheMapPut = method.getAnnotation(CacheMapPut.class);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        Object result = cacheAspect.handleCacheMapPut(joinPoint, cacheMapPut);

        assertEquals(Boolean.TRUE, result);
        verifyNoInteractions(rMap);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(rMap).put("1", "alice");
    }

    static class DemoService {

        @CacheMapPut(mapKey = "system:users", hashKey = "#{#user.id}", value = "#{#user.nickname}", condition = "#result == true")
        public Boolean updateUser(DemoUser user) {
            return Boolean.TRUE;
        }
    }

    static class DemoUser {

        private final Long id;
        private final String nickname;

        DemoUser(Long id, String nickname) {
            this.id = id;
            this.nickname = nickname;
        }

        public Long getId() {
            return id;
        }

        public String getNickname() {
            return nickname;
        }
    }
}
