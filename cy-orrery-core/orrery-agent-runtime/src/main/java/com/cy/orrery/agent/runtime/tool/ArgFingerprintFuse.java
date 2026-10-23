// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.tool;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 参数指纹熔断器：识别「相同参数原样重试」并硬熔断。
 * <p>
 * 指纹 = 参数按 key 排序序列化后 SHA-256 取前 16 位 hex（null / 空参数为 "empty"，
 * 序列化失败降级 identityHashCode）。按 (runId, 工具名, 指纹) 三元组计数连续失败；
 * 达阈值后入口直接拒绝（不调工具），LLM 改任何参数指纹即变、熔断自动解除。
 * 成功调用清零该指纹计数。计数存于运行上下文属性，随 run 生命周期自动消亡。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class ArgFingerprintFuse {

    private static final Logger log = LoggerFactory.getLogger(ArgFingerprintFuse.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 熔断计数表键名（检查点序列化器按此键做强类型注册）。 */
    public static final String FUSE_COUNTS_KEY_NAME = "tool.argFuseCounts";

    @SuppressWarnings("unchecked")
    private static final AttributeKey<Map<String, Integer>> FUSE_COUNTS =
            (AttributeKey) new AttributeKey(FUSE_COUNTS_KEY_NAME, Map.class);

    /** 指纹哈希算法。 */
    private static final String HASH_ALGORITHM = "SHA-256";

    /** 指纹 hex 长度（取哈希前 8 字节 = 16 位 hex，碰撞概率对熔断场景可忽略）。 */
    private static final int FINGERPRINT_BYTES = 8;

    /** 空参数指纹占位。 */
    private static final String EMPTY_FINGERPRINT = "empty";

    private final int threshold;

    /**
     * 构造熔断器。
     *
     * @param threshold 相同参数连续失败熔断阈值（≤0 关闭熔断）
     */
    public ArgFingerprintFuse(int threshold) {
        this.threshold = threshold;
    }

    /**
     * 计算参数指纹。
     *
     * @param arguments 已解析参数
     * @return 指纹 hex
     */
    public static String fingerprint(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return EMPTY_FINGERPRINT;
        }
        Map<String, Object> sorted = new TreeMap<>(arguments);
        try {
            String canonical = MAPPER.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < FINGERPRINT_BYTES; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            // 序列化失败降级：按排序后 toString 的内容哈希（确定性，同内容同指纹），
            // 避免实例指纹（每次调用必变）导致熔断计数永远无法累加。
            log.warn("参数指纹计算失败，降级为内容哈希指纹: {}", e.getMessage());
            return "fallback:" + Integer.toHexString(sorted.toString().hashCode());
        }
    }

    /**
     * 熔断检查：该 (runId, 工具名, 指纹) 是否已达拒绝阈值。
     *
     * @param ctx       运行上下文
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @return true 表示熔断命中，应拒绝执行
     */
    public boolean shouldReject(AgentRunContext ctx, String toolName, Map<String, Object> arguments) {
        if (threshold <= 0) {
            return false;
        }
        Integer count = counts(ctx).get(fuseKey(ctx, toolName, arguments));
        return count != null && count >= threshold;
    }

    /**
     * 失败记账（计数 +1）。
     *
     * @param ctx       运行上下文
     * @param toolName  工具名
     * @param arguments 已解析参数
     */
    public void recordFailure(AgentRunContext ctx, String toolName, Map<String, Object> arguments) {
        if (threshold <= 0) {
            return;
        }
        String key = fuseKey(ctx, toolName, arguments);
        counts(ctx).merge(key, 1, Integer::sum);
    }

    /**
     * 成功记账（清零该指纹计数）。
     *
     * @param ctx       运行上下文
     * @param toolName  工具名
     * @param arguments 已解析参数
     */
    public void recordSuccess(AgentRunContext ctx, String toolName, Map<String, Object> arguments) {
        if (threshold <= 0) {
            return;
        }
        counts(ctx).remove(fuseKey(ctx, toolName, arguments));
    }

    /**
     * 熔断键：runId + 工具名 + 参数指纹。
     *
     * @param ctx       运行上下文
     * @param toolName  工具名
     * @param arguments 已解析参数
     * @return 熔断键
     */
    private String fuseKey(AgentRunContext ctx, String toolName, Map<String, Object> arguments) {
        return ctx.getRunId() + "|" + toolName + "|" + fingerprint(arguments);
    }

    /**
     * 计数表（惰性初始化，并发安全）。
     *
     * @param ctx 运行上下文
     * @return 计数表
     */
    private Map<String, Integer> counts(AgentRunContext ctx) {
        Map<String, Integer> counts = ctx.getAttr(FUSE_COUNTS);
        if (counts == null) {
            counts = new ConcurrentHashMap<>();
            ctx.putAttr(FUSE_COUNTS, counts);
        }
        return counts;
    }
}
