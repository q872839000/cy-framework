package com.cy.redis.aspect;

import com.cy.redis.annotation.CacheEvict;
import com.cy.redis.annotation.CacheMapEvict;
import com.cy.redis.annotation.CacheMapPut;
import com.cy.redis.annotation.CachePut;
import com.cy.redis.annotation.CacheResult;
import com.cy.redis.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 缓存切面处理器。
 * <p>
 * 负责处理自定义缓存注解，并在事务场景下将写入/删除操作延迟到事务提交后执行，
 * 避免数据库回滚后 Redis 缓存提前可见。
 * </p>
 *
 * @author pcy
 * @see CacheResult
 * @see CachePut
 * @see CacheEvict
 */
@Slf4j
@Aspect
public class CacheAspect {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("#\\{([^}]+)\\}");

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 处理 @CacheResult 注解。
     */
    @Around("@annotation(cacheResult)")
    public Object handleCacheResult(ProceedingJoinPoint joinPoint, CacheResult cacheResult) throws Throwable {
        String cacheKey = buildCacheKey(joinPoint, cacheResult.keyPrefix(), cacheResult.key());

        if (StringUtils.isNotBlank(cacheResult.condition())) {
            boolean condition = evaluateCondition(joinPoint, cacheResult.condition(), null);
            if (!condition) {
                return joinPoint.proceed();
            }
        }

        Object cachedResult = RedisUtils.getCacheObject(cacheKey);
        if (cachedResult != null) {
            log.debug("Cache hit for key: {}", cacheKey);
            return cachedResult;
        }

        Object result = joinPoint.proceed();
        if (result != null || cacheResult.cacheNull()) {
            executeAfterCommitOrNow(() -> {
                if (cacheResult.expire() <= 0) {
                    RedisUtils.setCacheObject(cacheKey, result);
                    log.debug("Cache stored for key: {}, expire: never", cacheKey);
                } else {
                    Duration duration = Duration.of(cacheResult.expire(), cacheResult.timeUnit().toChronoUnit());
                    RedisUtils.setCacheObject(cacheKey, result, duration);
                    log.debug("Cache stored for key: {}, expire: {}s", cacheKey, duration.getSeconds());
                }
            });
        }
        return result;
    }

    /**
     * 处理 @CachePut 注解。
     */
    @Around("@annotation(cachePut)")
    public Object handleCachePut(ProceedingJoinPoint joinPoint, CachePut cachePut) throws Throwable {
        Object result = joinPoint.proceed();

        if (StringUtils.isNotBlank(cachePut.condition())) {
            boolean condition = evaluateCondition(joinPoint, cachePut.condition(), result);
            if (!condition) {
                return result;
            }
        }

        if (result != null || cachePut.cacheNull()) {
            String cacheKey = buildCacheKey(joinPoint, cachePut.keyPrefix(), cachePut.key(), result);
            executeAfterCommitOrNow(() -> {
                if (cachePut.expire() <= 0) {
                    RedisUtils.setCacheObject(cacheKey, result);
                    log.debug("Cache updated for key: {}, expire: never", cacheKey);
                } else {
                    Duration duration = Duration.of(cachePut.expire(), cachePut.timeUnit().toChronoUnit());
                    RedisUtils.setCacheObject(cacheKey, result, duration);
                    log.debug("Cache updated for key: {}, expire: {}s", cacheKey, duration.getSeconds());
                }
            });
        }
        return result;
    }

    /**
     * 处理 @CacheEvict 注解。
     */
    @Around("@annotation(cacheEvict)")
    public Object handleCacheEvict(ProceedingJoinPoint joinPoint, CacheEvict cacheEvict) throws Throwable {
        if (cacheEvict.beforeInvocation()) {
            evictCache(joinPoint, cacheEvict, null);
        }

        Object result = joinPoint.proceed();

        if (!cacheEvict.beforeInvocation()) {
            if (StringUtils.isNotBlank(cacheEvict.condition())) {
                boolean condition = evaluateCondition(joinPoint, cacheEvict.condition(), result);
                if (!condition) {
                    return result;
                }
            }
            executeAfterCommitOrNow(() -> evictCache(joinPoint, cacheEvict, result));
        }
        return result;
    }

    /**
     * 处理 @CacheMapPut 注解。
     */
    @Around("@annotation(cacheMapPut)")
    public Object handleCacheMapPut(ProceedingJoinPoint joinPoint, CacheMapPut cacheMapPut) throws Throwable {
        Object result = joinPoint.proceed();

        if (StringUtils.isNotBlank(cacheMapPut.condition())) {
            boolean condition = evaluateCondition(joinPoint, cacheMapPut.condition(), result);
            if (!condition) {
                return result;
            }
        }

        String mapKey = resolveKeyExpression(joinPoint, cacheMapPut.mapKey(), result);
        String hashKey = resolveKeyExpression(joinPoint, cacheMapPut.hashKey(), result);
        String value = resolveKeyExpression(joinPoint, cacheMapPut.value(), result);

        if (StringUtils.isNotBlank(mapKey) && StringUtils.isNotBlank(hashKey)) {
            executeAfterCommitOrNow(() -> {
                RedisUtils.setCacheMapValue(mapKey, hashKey, value);
                log.debug("CacheMap updated: mapKey={}, hashKey={}, value={}", mapKey, hashKey, value);
            });
        }
        return result;
    }

    /**
     * 处理 @CacheMapEvict 注解。
     */
    @Around("@annotation(cacheMapEvict)")
    public Object handleCacheMapEvict(ProceedingJoinPoint joinPoint, CacheMapEvict cacheMapEvict) throws Throwable {
        Object result = joinPoint.proceed();

        if (StringUtils.isNotBlank(cacheMapEvict.condition())) {
            boolean condition = evaluateCondition(joinPoint, cacheMapEvict.condition(), result);
            if (!condition) {
                return result;
            }
        }

        String mapKey = resolveKeyExpression(joinPoint, cacheMapEvict.mapKey(), result);
        if (cacheMapEvict.allEntries()) {
            executeAfterCommitOrNow(() -> {
                RedisUtils.deleteObject(mapKey);
                log.debug("CacheMap cleared: mapKey={}", mapKey);
            });
            return result;
        }

        String hashKey = resolveKeyExpression(joinPoint, cacheMapEvict.hashKey(), result);
        if (StringUtils.isNotBlank(mapKey) && StringUtils.isNotBlank(hashKey)) {
            executeAfterCommitOrNow(() -> {
                RedisUtils.delCacheMapValue(mapKey, hashKey);
                log.debug("CacheMap evicted: mapKey={}, hashKey={}", mapKey, hashKey);
            });
        }
        return result;
    }

    /**
     * 执行缓存删除。
     */
    private void evictCache(ProceedingJoinPoint joinPoint, CacheEvict cacheEvict, Object result) {
        if (cacheEvict.allEntries()) {
            if (StringUtils.isBlank(cacheEvict.keyPrefix())) {
                log.warn("Skip cache eviction because keyPrefix is blank and allEntries=true");
                return;
            }
            String pattern = cacheEvict.keyPrefix() + "*";
            RedisUtils.deleteKeys(pattern);
            log.debug("Cache evicted for pattern: {}", pattern);
            return;
        }

        Arrays.stream(cacheEvict.keys())
            .filter(StringUtils::isNotBlank)
            .map(key -> buildCacheKey(joinPoint, cacheEvict.keyPrefix(), key, result))
            .forEach(cacheKey -> {
                boolean deleted = RedisUtils.deleteObject(cacheKey);
                log.debug("Cache evicted for key: {}, result: {}", cacheKey, deleted);
            });
    }

    /**
     * 构建缓存 key。
     */
    private String buildCacheKey(ProceedingJoinPoint joinPoint, String keyPrefix, String keyExpression) {
        return buildCacheKey(joinPoint, keyPrefix, keyExpression, null);
    }

    /**
     * 构建缓存 key，支持 result 变量。
     */
    private String buildCacheKey(ProceedingJoinPoint joinPoint, String keyPrefix, String keyExpression, Object result) {
        String resolvedKey = resolveKeyExpression(joinPoint, keyExpression, result);
        if (StringUtils.isNotBlank(keyPrefix)) {
            return keyPrefix + ":" + resolvedKey;
        }
        return resolvedKey;
    }

    /**
     * 解析 key 模板表达式。
     */
    private String resolveKeyExpression(ProceedingJoinPoint joinPoint, String keyExpression, Object result) {
        if (StringUtils.isBlank(keyExpression)) {
            return "";
        }

        EvaluationContext context = createEvaluationContext(joinPoint, result);
        Matcher matcher = TEMPLATE_PATTERN.matcher(keyExpression);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String spelExpression = matcher.group(1);
            try {
                Object value = parser.parseExpression(spelExpression).getValue(context);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(value != null ? value.toString() : "null"));
            } catch (Exception e) {
                log.warn("Failed to evaluate SpEL expression: {}, error: {}", spelExpression, e.getMessage());
                matcher.appendReplacement(buffer, "null");
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 评估条件表达式。
     */
    private boolean evaluateCondition(ProceedingJoinPoint joinPoint, String condition, Object result) {
        if (StringUtils.isBlank(condition)) {
            return true;
        }

        try {
            EvaluationContext context = createEvaluationContext(joinPoint, result);
            Boolean value = parser.parseExpression(condition).getValue(context, Boolean.class);
            return value != null && value;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition: {}, error: {}", condition, e.getMessage());
            return false;
        }
    }

    /**
     * 创建 SpEL 上下文。
     */
    private EvaluationContext createEvaluationContext(ProceedingJoinPoint joinPoint, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method, args, nameDiscoverer);
        if (result != null) {
            context.setVariable("result", result);
        }
        return context;
    }

    /**
     * 在事务提交后执行缓存操作；无事务时立即执行。
     */
    private void executeAfterCommitOrNow(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
            && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runnable.run();
                }
            });
            return;
        }
        runnable.run();
    }
}
