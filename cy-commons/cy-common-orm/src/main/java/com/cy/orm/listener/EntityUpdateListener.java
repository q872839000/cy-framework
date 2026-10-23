package com.cy.orm.listener;

import com.cy.core.context.AuditUserResolver;
import com.cy.core.context.InvocationScope;
import com.cy.orm.core.domain.BaseEntity;
import com.mybatisflex.annotation.UpdateListener;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Entity实体类全局更新数据监听器
 * <p>
 * 自动填充更新人、更新时间等字段。
 * 支持超级管理员（不存储在数据库中的虚拟用户）。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
public class EntityUpdateListener implements UpdateListener {

    private static final Logger log = LoggerFactory.getLogger(EntityUpdateListener.class);

    @Override
    public void onUpdate(Object entity) {
        try {
            if (ObjectUtils.isNotEmpty(entity) && (entity instanceof BaseEntity baseEntity)) {
                Long loginUserId = AuditUserResolver.resolveUserId(InvocationScope.current());
                
                // 处理用户ID：
                // 超级管理员（ID为-1L）使用0L，因为超级管理员不入库
                if (loginUserId != null) {
                    baseEntity.setUpdateUser(loginUserId);
                }
                
                baseEntity.setUpdateTime(Timestamp.from(Instant.now()));
            }
        } catch (RuntimeException e) {
            log.error("Failed to populate audit fields on update: entityType={}",
                    entity != null ? entity.getClass().getName() : "null", e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to populate audit fields on update: entityType={}",
                    entity != null ? entity.getClass().getName() : "null", e);
            throw new IllegalStateException("Failed to populate audit fields on update", e);
        }
    }
}
