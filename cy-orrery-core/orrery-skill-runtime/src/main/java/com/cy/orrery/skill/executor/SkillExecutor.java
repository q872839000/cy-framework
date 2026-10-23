// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.executor;

import com.cy.core.exception.CYException;
import com.cy.orrery.skill.registry.SkillRegistry;
import com.cy.orrery.skill.spi.Skill;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 技能执行器
 * <p>
 * 接收 skillId 和输入参数，从 SkillRegistry 查找对应 Skill 实例并执行。
 * 包含执行耗时统计、异常捕获和日志审计，保障 Skill 执行的可观测性。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Slf4j
public class SkillExecutor {

    private final SkillRegistry skillRegistry;

    /**
     * 构造技能执行器。
     *
     * @param skillRegistry 技能注册表
     */
    public SkillExecutor(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * 执行指定 Skill
     *
     * @param skillId 技能ID
     * @param input   输入参数
     * @return 执行结果
     */
    public SkillExecutionResult execute(String skillId, Map<String, Object> input) {
        Skill skill = skillRegistry.getSkill(skillId);
        if (skill == null) {
            log.warn("Skill 未找到: skillId={}", skillId);
            return SkillExecutionResult.failure(skillId, 50301, resolveMessage(50301));
        }

        log.info("开始执行 Skill: skillId={}, name={}", skillId, skill.getName());
        long start = System.currentTimeMillis();

        try {
            Map<String, Object> output = skill.execute(input);
            long duration = System.currentTimeMillis() - start;
            log.info("Skill 执行成功: skillId={}, durationMs={}", skillId, duration);
            return SkillExecutionResult.success(skillId, output, duration);
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Skill 执行失败: skillId={}, durationMs={}", skillId, duration, e);
            return SkillExecutionResult.failure(skillId, 50303,
                    resolveMessage(50303), duration);
        }
    }

    /** 按错误码解析 i18n 消息。 */
    private String resolveMessage(int errorCode, Object... args) {
        return new CYException(errorCode, args).getMessage();
    }
}
