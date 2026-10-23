package com.cy.userfill.aspect;

import com.cy.core.constants.Constants;
import com.cy.redis.utils.RedisUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserNameFillAspectTest {

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(RedisUtils.class, "CLIENT", null);
    }

    @Test
    void shouldBatchLoadOnlyRequiredUserNames() throws Throwable {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<Object, Object> rMap = mock(RMap.class);
        when(redissonClient.getMap(Constants.USER_CACHE_KEY)).thenReturn(rMap);
        when(rMap.getAll(argThat(keys -> keys.equals(Set.of("1", "2")))))
            .thenReturn(Map.of("1", "张三", "2", "李四"));
        ReflectionTestUtils.setField(RedisUtils.class, "CLIENT", redissonClient);

        UserNameFillAspect aspect = new UserNameFillAspect();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);

        DemoUserRecord first = new DemoUserRecord(1L, 2L);
        DemoWrapper second = new DemoWrapper(new DemoUserRecord(2L, 0L));
        when(joinPoint.proceed()).thenReturn(List.of(first, second));

        Object result = aspect.around(joinPoint, null);

        assertEquals(List.of(first, second), result);
        assertEquals("张三", first.getCreateName());
        assertEquals("李四", first.getUpdateName());
        assertEquals("李四", second.getRecord().getCreateName());
        assertEquals("系统", second.getRecord().getUpdateName());
        verify(rMap).getAll(argThat(keys -> keys.equals(Set.of("1", "2"))));
    }

    static class DemoWrapper {

        private final DemoUserRecord record;

        DemoWrapper(DemoUserRecord record) {
            this.record = record;
        }

        public DemoUserRecord getRecord() {
            return record;
        }
    }

    static class DemoUserRecord {

        private final Long createUser;
        private final Long updateUser;
        private String createName;
        private String updateName;

        DemoUserRecord(Long createUser, Long updateUser) {
            this.createUser = createUser;
            this.updateUser = updateUser;
        }

        public Long getCreateUser() {
            return createUser;
        }

        public Long getUpdateUser() {
            return updateUser;
        }

        public String getCreateName() {
            return createName;
        }

        public void setCreateName(String createName) {
            this.createName = createName;
        }

        public String getUpdateName() {
            return updateName;
        }

        public void setUpdateName(String updateName) {
            this.updateName = updateName;
        }
    }
}
