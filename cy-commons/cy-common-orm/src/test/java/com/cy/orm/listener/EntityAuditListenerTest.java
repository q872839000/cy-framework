package com.cy.orm.listener;

import com.cy.core.context.AuthUser;
import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationScope;
import com.cy.orm.core.domain.BaseEntity;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityAuditListenerTest {

    private final EntityInsertListener insertListener = new EntityInsertListener();
    private final EntityUpdateListener updateListener = new EntityUpdateListener();

    @Test
    void shouldPopulateAuditFieldsOnInsert() {
        DemoEntity entity = new DemoEntity();

        try (InvocationScope ignored = InvocationScope.open(authenticatedContext(123L, false))) {
            insertListener.onInsert(entity);
        }

        assertEquals(123L, entity.getCreateUser());
        assertEquals(123L, entity.getUpdateUser());
        assertNotNull(entity.getCreateTime());
        assertNotNull(entity.getUpdateTime());
        assertEquals(entity.getCreateTime(), entity.getUpdateTime());
    }

    @Test
    void shouldPreservePresetCreateTimeOnInsert() {
        DemoEntity entity = new DemoEntity();
        Timestamp createTime = Timestamp.valueOf("2026-04-14 10:15:30");
        entity.setCreateTime(createTime);

        try (InvocationScope ignored = InvocationScope.open(authenticatedContext(123L, false))) {
            insertListener.onInsert(entity);
        }

        assertSame(createTime, entity.getCreateTime());
        assertEquals(createTime, entity.getUpdateTime());
    }

    @Test
    void shouldMapSuperAdminToSystemAuditUserOnUpdate() {
        DemoEntity entity = new DemoEntity();

        try (InvocationScope ignored = InvocationScope.open(authenticatedContext(-1L, true))) {
            updateListener.onUpdate(entity);
        }

        assertEquals(0L, entity.getUpdateUser());
        assertNotNull(entity.getUpdateTime());
    }

    @Test
    void shouldRethrowInsertListenerFailure() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            try (InvocationScope ignored = InvocationScope.open(authenticatedContext(123L, false))) {
                insertListener.onInsert(new FailingInsertEntity());
            }
        });

        assertEquals("insert failure", exception.getMessage());
    }

    @Test
    void shouldRethrowUpdateListenerFailure() {
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            try (InvocationScope ignored = InvocationScope.open(authenticatedContext(123L, false))) {
                updateListener.onUpdate(new FailingUpdateEntity());
            }
        });

        assertEquals("update failure", exception.getMessage());
    }

    private InvocationContext authenticatedContext(Long userId, boolean superAdmin) {
        AuthUser authUser = new AuthUser();
        authUser.setUserId(userId);
        authUser.setUsername("tester");
        authUser.setNickname("tester");
        authUser.setSuperAdmin(superAdmin);
        return InvocationContext.builder()
                .authUser(authUser)
                .traceId("trace")
                .spanId("span")
                .source(InvocationContext.SOURCE_EXTERNAL)
                .authenticated(true)
                .gatewayForwarded(false)
                .build();
    }

    private static class DemoEntity extends BaseEntity {
    }

    private static class FailingInsertEntity extends DemoEntity {
        @Override
        public void setCreateTime(Timestamp createTime) {
            throw new RuntimeException("insert failure");
        }
    }

    private static class FailingUpdateEntity extends DemoEntity {
        @Override
        public void setUpdateTime(Timestamp updateTime) {
            throw new RuntimeException("update failure");
        }
    }
}
