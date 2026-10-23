package com.cy.userfill.aspect;

import com.cy.core.constants.Constants;
import com.cy.redis.utils.RedisUtils;
import com.cy.userfill.annotation.FillUserName;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户名自动填充 AOP 切面。
 * <p>
 * 拦截标注了 {@link FillUserName} 注解的方法，统一扫描返回结果中的 createUser / updateUser 字段，
 * 批量从 Redis 查询用户名映射后再回填到 createName / updateName 字段。
 * </p>
 * <p>
 * 当前实现以“整棵结果对象图只做一次批量查询”为原则：
 * <ul>
 *     <li>先递归采集所有用户 ID</li>
 *     <li>再批量查询 Redis Hash，避免逐对象重复访问 Redis</li>
 *     <li>最后统一回填用户名字段</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/01/09
 */
@Slf4j
@Aspect
@Component
public class UserNameFillAspect implements Ordered {

    /**
     * 系统保留用户名称映射。
     */
    private static final Map<Long, String> SYSTEM_USER_NAMES = new ConcurrentHashMap<>();

    static {
        SYSTEM_USER_NAMES.put(0L, "系统");
        SYSTEM_USER_NAMES.put(Constants.SUPER_ADMIN_USER_ID, Constants.SUPER_ADMIN_NICKNAME);
    }

    @Around("@annotation(fillUserName)")
    public Object around(ProceedingJoinPoint joinPoint, FillUserName fillUserName) throws Throwable {
        Object result = joinPoint.proceed();
        if (result == null) {
            return null;
        }

        try {
            Set<Long> userIds = new HashSet<>();
            collectUserIds(result, newIdentitySet(), userIds);
            if (userIds.isEmpty()) {
                return result;
            }

            Map<String, String> userNameMap = loadUserNameMap(userIds);
            fillUserNames(result, userNameMap, newIdentitySet());
        } catch (Exception e) {
            log.error("用户名填充失败", e);
        }
        return result;
    }

    /**
     * 递归收集对象图中的用户 ID。
     *
     * @param obj              当前对象
     * @param processedObjects 已处理对象集合
     * @param userIds          用户ID集合
     */
    private void collectUserIds(Object obj, Set<Object> processedObjects, Set<Long> userIds) {
        if (obj == null || processedObjects.contains(obj)) {
            return;
        }

        processedObjects.add(obj);
        try {
            if (obj instanceof Collection<?> collection) {
                for (Object item : collection) {
                    collectUserIds(item, processedObjects, userIds);
                }
                return;
            }

            if (isPageResult(obj)) {
                Field recordsField = findField(obj.getClass(), "records");
                if (recordsField != null) {
                    recordsField.setAccessible(true);
                    Object records = recordsField.get(obj);
                    collectUserIds(records, processedObjects, userIds);
                }
                return;
            }

            userIds.addAll(collectUserIdsFromCurrentObject(obj));
            collectUserIdsFromFields(obj, processedObjects, userIds);
        } catch (Exception e) {
            log.debug("收集用户ID失败: {}", e.getMessage());
        } finally {
            processedObjects.remove(obj);
        }
    }

    /**
     * 递归填充对象图中的用户名字段。
     *
     * @param obj              当前对象
     * @param userNameMap      用户名映射
     * @param processedObjects 已处理对象集合
     */
    private void fillUserNames(Object obj, Map<String, String> userNameMap, Set<Object> processedObjects) {
        if (obj == null || processedObjects.contains(obj)) {
            return;
        }

        processedObjects.add(obj);
        try {
            if (obj instanceof Collection<?> collection) {
                for (Object item : collection) {
                    fillUserNames(item, userNameMap, processedObjects);
                }
                return;
            }

            if (isPageResult(obj)) {
                Field recordsField = findField(obj.getClass(), "records");
                if (recordsField != null) {
                    recordsField.setAccessible(true);
                    Object records = recordsField.get(obj);
                    fillUserNames(records, userNameMap, processedObjects);
                }
                return;
            }

            fillCurrentObject(obj, userNameMap);
            fillChildFields(obj, userNameMap, processedObjects);
        } catch (Exception e) {
            log.debug("填充用户名失败: {}", e.getMessage());
        } finally {
            processedObjects.remove(obj);
        }
    }

    /**
     * 从当前对象提取 createUser / updateUser。
     *
     * @param obj 当前对象
     * @return 用户ID集合
     */
    private Set<Long> collectUserIdsFromCurrentObject(Object obj) {
        Set<Long> userIds = new HashSet<>();
        if (obj == null) {
            return userIds;
        }

        try {
            Class<?> clazz = obj.getClass();
            collectUserIdByField(obj, clazz, "createUser", userIds);
            collectUserIdByField(obj, clazz, "updateUser", userIds);
        } catch (Exception e) {
            log.debug("收集当前对象用户ID失败: {}", e.getMessage());
        }
        return userIds;
    }

    /**
     * 递归采集子字段中的用户 ID。
     *
     * @param obj              当前对象
     * @param processedObjects 已处理对象集合
     * @param userIds          用户ID集合
     */
    private void collectUserIdsFromFields(Object obj, Set<Object> processedObjects, Set<Long> userIds) {
        Class<?> clazz = obj.getClass();
        if (isSkipType(clazz)) {
            return;
        }

        try {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object fieldValue = field.get(obj);
                if (fieldValue != null) {
                    collectUserIds(fieldValue, processedObjects, userIds);
                }
            }
        } catch (Exception e) {
            log.debug("递归收集字段用户ID失败: {}", e.getMessage());
        }
    }

    /**
     * 填充当前对象的 createName / updateName。
     *
     * @param obj         当前对象
     * @param userNameMap 用户名映射
     */
    private void fillCurrentObject(Object obj, Map<String, String> userNameMap) {
        if (obj == null || CollectionUtils.isEmpty(userNameMap)) {
            return;
        }

        try {
            Class<?> clazz = obj.getClass();
            fillUserNameField(obj, clazz, "createUser", "createName", userNameMap);
            fillUserNameField(obj, clazz, "updateUser", "updateName", userNameMap);
        } catch (Exception e) {
            log.debug("填充当前对象用户名失败: {}", e.getMessage());
        }
    }

    /**
     * 递归填充子字段中的用户名。
     *
     * @param obj              当前对象
     * @param userNameMap      用户名映射
     * @param processedObjects 已处理对象集合
     */
    private void fillChildFields(Object obj, Map<String, String> userNameMap, Set<Object> processedObjects) {
        Class<?> clazz = obj.getClass();
        if (isSkipType(clazz)) {
            return;
        }

        try {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object fieldValue = field.get(obj);
                if (fieldValue != null) {
                    fillUserNames(fieldValue, userNameMap, processedObjects);
                }
            }
        } catch (Exception e) {
            log.debug("递归填充字段用户名失败: {}", e.getMessage());
        }
    }

    /**
     * 批量加载用户名映射。
     *
     * @param userIds 用户ID集合
     * @return 用户名映射
     */
    private Map<String, String> loadUserNameMap(Set<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Map.of();
        }

        Map<String, String> userNameMap = new HashMap<>();
        Set<String> redisUserIds = new HashSet<>();
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            String systemUserName = SYSTEM_USER_NAMES.get(userId);
            if (systemUserName != null) {
                userNameMap.put(userId.toString(), systemUserName);
            } else {
                redisUserIds.add(userId.toString());
            }
        }

        if (!redisUserIds.isEmpty()) {
            Map<String, String> redisValues = RedisUtils.getMultiCacheMapValue(Constants.USER_CACHE_KEY, redisUserIds);
            if (redisValues != null && !redisValues.isEmpty()) {
                userNameMap.putAll(redisValues);
            }
        }
        return userNameMap;
    }

    /**
     * 按字段名采集用户ID。
     *
     * @param obj      当前对象
     * @param clazz    对象类型
     * @param fieldName 字段名
     * @param userIds  用户ID集合
     */
    private void collectUserIdByField(Object obj, Class<?> clazz, String fieldName, Set<Long> userIds) {
        Field field = findField(clazz, fieldName);
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            Object value = field.get(obj);
            if (value instanceof Long userId) {
                userIds.add(userId);
            }
        } catch (Exception e) {
            log.debug("收集字段 {} 用户ID失败: {}", fieldName, e.getMessage());
        }
    }

    /**
     * 填充单个用户名字段。
     *
     * @param obj              当前对象
     * @param clazz            对象类型
     * @param userIdFieldName  用户ID字段名
     * @param userNameFieldName 用户名字段名
     * @param userNameMap      用户名映射
     */
    private void fillUserNameField(Object obj, Class<?> clazz, String userIdFieldName, String userNameFieldName, Map<String, String> userNameMap) {
        try {
            Field userIdField = findField(clazz, userIdFieldName);
            if (userIdField == null) {
                return;
            }

            userIdField.setAccessible(true);
            Object userId = userIdField.get(obj);
            if (!(userId instanceof Long userIdLong)) {
                return;
            }

            String userName = userNameMap.get(userIdLong.toString());
            if (userName == null) {
                return;
            }

            Field userNameField = findField(clazz, userNameFieldName);
            if (userNameField != null) {
                userNameField.setAccessible(true);
                userNameField.set(obj, userName);
                return;
            }

            addDynamicField(obj, userNameFieldName, userName);
        } catch (Exception e) {
            log.debug("填充字段 {} 失败: {}", userNameFieldName, e.getMessage());
        }
    }

    /**
     * 动态向 Map 对象写入字段。
     *
     * @param obj       当前对象
     * @param fieldName 字段名
     * @param value     字段值
     */
    @SuppressWarnings("unchecked")
    private void addDynamicField(Object obj, String fieldName, String value) {
        try {
            if (obj instanceof Map<?, ?> map) {
                ((Map<String, Object>) map).put(fieldName, value);
                log.debug("动态写入 Map 字段: {}={}", fieldName, value);
            }
        } catch (Exception e) {
            log.debug("动态写入字段失败: {}", e.getMessage());
        }
    }

    /**
     * 判断是否为分页结果类型。
     *
     * @param obj 当前对象
     * @return true=分页结果
     */
    private boolean isPageResult(Object obj) {
        if (obj == null) {
            return false;
        }
        String className = obj.getClass().getSimpleName().toLowerCase();
        return className.contains("page") || className.contains("result");
    }

    /**
     * 是否为无需继续递归的基础类型。
     *
     * @param clazz 类型
     * @return true=跳过
     */
    private boolean isSkipType(Class<?> clazz) {
        return clazz.isPrimitive()
            || clazz.isEnum()
            || clazz.equals(String.class)
            || Number.class.isAssignableFrom(clazz)
            || clazz.equals(Boolean.class)
            || clazz.equals(Date.class)
            || clazz.getName().startsWith("java.")
            || clazz.getName().startsWith("javax.")
            || clazz.getName().startsWith("org.springframework.");
    }

    /**
     * 查找字段（包含父类）。
     *
     * @param clazz     当前类型
     * @param fieldName 字段名
     * @return 字段对象
     */
    private Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 创建基于对象身份的去重集合。
     *
     * @return Set
     */
    private Set<Object> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
