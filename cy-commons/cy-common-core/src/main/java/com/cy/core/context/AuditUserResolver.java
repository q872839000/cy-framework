package com.cy.core.context;

import com.cy.core.constants.Constants;

/**
 * 审计用户 ID 解析器。
 * <p>
 * 从 {@link InvocationContext} 中提取用于 ORM 审计字段（createUser / updateUser）的用户 ID。
 * 特殊处理：未认证用户和超级管理员（不入库）统一映射为 {@code 0L}。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 * @see com.cy.orm.listener.EntityInsertListener
 * @see com.cy.orm.listener.EntityUpdateListener
 */
public final class AuditUserResolver {

    /** 工具类，禁止实例化 */
    private AuditUserResolver() {
    }

    /**
     * 解析审计用户 ID。
     * <p>
     * 规则：
     * <ul>
     *   <li>上下文为 {@code null} 或用户 ID 为 {@code null} → 返回 {@code 0L}</li>
     *   <li>超级管理员（{@link Constants#SUPER_ADMIN_USER_ID}）→ 返回 {@code 0L}（超级管理员不入库）</li>
     *   <li>其他 → 返回实际用户 ID</li>
     * </ul>
     * </p>
     *
     * @param context 调用上下文，可为 {@code null}
     * @return 用于审计字段的用户 ID
     */
    public static Long resolveUserId(InvocationContext context) {
        if (context == null || context.getUserId() == null) {
            return 0L;
        }
        if (Constants.SUPER_ADMIN_USER_ID.equals(context.getUserId())) {
            return 0L;
        }
        return context.getUserId();
    }
}
