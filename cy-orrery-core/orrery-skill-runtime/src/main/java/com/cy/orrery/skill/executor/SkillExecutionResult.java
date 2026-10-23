// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.executor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 技能执行结果
 * <p>
 * 封装 Skill 执行后的输出数据、成功/失败状态、错误信息和执行耗时。
 * SkillExecutor 统一返回此对象，调用方据此判断执行结果并进行后续处理。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionResult {

    /**
     * 技能ID
     */
    private String skillId;

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 输出数据
     */
    private Map<String, Object> output;

    /**
     * 错误码（失败时返回）
     */
    private Integer errorCode;

    /**
     * 错误消息（失败时返回）
     */
    private String errorMessage;

    /**
     * 执行耗时（毫秒）
     */
    private long durationMs;

    /**
     * 构建成功结果
     *
     * @param skillId    技能ID
     * @param output     输出数据
     * @param durationMs 执行耗时
     * @return 成功结果
     */
    public static SkillExecutionResult success(String skillId, Map<String, Object> output, long durationMs) {
        return SkillExecutionResult.builder()
                .skillId(skillId)
                .success(true)
                .output(output)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 构建失败结果
     *
     * @param skillId      技能ID
     * @param errorCode    错误码
     * @param errorMessage 错误消息
     * @return 失败结果
     */
    public static SkillExecutionResult failure(String skillId, int errorCode, String errorMessage) {
        return SkillExecutionResult.builder()
                .skillId(skillId)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 构建失败结果（含耗时）
     *
     * @param skillId      技能ID
     * @param errorCode    错误码
     * @param errorMessage 错误消息
     * @param durationMs   执行耗时
     * @return 失败结果
     */
    public static SkillExecutionResult failure(String skillId, int errorCode, String errorMessage, long durationMs) {
        return SkillExecutionResult.builder()
                .skillId(skillId)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .build();
    }
}
