package com.cy.mq.aspect;

import com.cy.mq.annotation.MqPublish;
import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqTemplate;
import lombok.RequiredArgsConstructor;
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
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQ 发布切面
 *
 * @author pcy
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class MqPublishAspect {

    /** MQ 模板 */
    private final MqTemplate mqTemplate;

    /** MQ 配置属性 */
    private final MqProperties properties;

    /** SpEL 解析器 */
    private final ExpressionParser parser = new SpelExpressionParser();

    /** 参数名解析器 */
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * SpEL 表达式缓存。
     * <p>
     * 缓存已解析的表达式，避免重复解析，提升性能。
     * Key: 表达式字符串，Value: 解析后的 Expression 对象。
     * </p>
     */
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * 处理 @MqPublish 注解
     */
    @Around("@annotation(mqPublish)")
    public Object around(ProceedingJoinPoint joinPoint, MqPublish mqPublish) throws Throwable {
        Object result = joinPoint.proceed();

        Object payload = resolvePayload(joinPoint, mqPublish, result);
        if (payload == null && !mqPublish.publishNull()) {
            return result;
        }

        String topic = resolveStringExpression(joinPoint, mqPublish.topic(), result);
        if (StringUtils.isBlank(topic)) {
            log.warn("MqPublish topic 解析为空，跳过发布");
            return result;
        }

        String key = resolveStringExpression(joinPoint, mqPublish.key(), result);
        if (StringUtils.isBlank(key)) {
            key = null;
        }

        MqDriverType driver = resolveDriver(mqPublish.driver());
        if (key == null) {
            mqTemplate.publish(driver, topic, payload);
        } else {
            mqTemplate.publish(driver, topic, key, payload);
        }

        return result;
    }

    private MqDriverType resolveDriver(String driver) {
        if (StringUtils.isBlank(driver)) {
            return null;
        }
        return MqDriverType.valueOf(driver.trim().toUpperCase());
    }

    /**
     * 解析消息体
     */
    private Object resolvePayload(ProceedingJoinPoint joinPoint, MqPublish mqPublish, Object result) {
        if (StringUtils.isBlank(mqPublish.payload())) {
            return result;
        }

        String payloadExpression = mqPublish.payload().trim();
        // 纯 #{...} 表达式直接返回对象
        if (payloadExpression.startsWith("#{") && payloadExpression.endsWith("}") && payloadExpression.indexOf("#{") == 0
                && payloadExpression.lastIndexOf("}") == payloadExpression.length() - 1) {
            String spel = payloadExpression.substring(2, payloadExpression.length() - 1);
            try {
                EvaluationContext context = createEvaluationContext(joinPoint, result);
                Expression expression = parseExpression(spel);
                return expression.getValue(context);
            } catch (Exception e) {
                if (properties.isSpelFailFast()) {
                    throw new IllegalStateException("SpEL 表达式解析失败: " + spel, e);
                }
                log.warn("SpEL 表达式解析失败，使用 null 替代: expression={}, error={}", spel, e.getMessage());
                return null;
            }
        }

        // 否则按字符串模板替换所有 #{...}
        String resolved = resolveStringExpression(joinPoint, payloadExpression, result);
        return resolved;
    }

    /**
     * 解析字符串模板表达式
     */
    private String resolveStringExpression(ProceedingJoinPoint joinPoint, String expression, Object result) {
        if (StringUtils.isBlank(expression)) {
            return "";
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("#\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(expression);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String spelExpression = matcher.group(1);
            try {
                EvaluationContext context = createEvaluationContext(joinPoint, result);
                Expression parsedExpression = parseExpression(spelExpression);
                Object value = parsedExpression.getValue(context);
                String replacement = value != null ? value.toString() : "null";
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            } catch (Exception e) {
                if (properties.isSpelFailFast()) {
                    throw new IllegalStateException("SpEL 表达式解析失败: " + spelExpression, e);
                }
                log.warn("SpEL 表达式解析失败，使用 null 替代: expression={}, error={}", spelExpression, e.getMessage());
                matcher.appendReplacement(sb, "null");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 创建 SpEL 上下文
     */
    private EvaluationContext createEvaluationContext(ProceedingJoinPoint joinPoint, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method, args, nameDiscoverer);
        context.setVariable("result", result);
        return context;
    }

    /**
     * 解析 SpEL 表达式（带缓存）。
     * <p>
     * 使用 ConcurrentHashMap 缓存已解析的表达式，避免重复解析，提升性能。
     * </p>
     *
     * @param expressionString 表达式字符串
     * @return 解析后的 Expression 对象
     */
    private Expression parseExpression(String expressionString) {
        return expressionCache.computeIfAbsent(expressionString, parser::parseExpression);
    }
}
