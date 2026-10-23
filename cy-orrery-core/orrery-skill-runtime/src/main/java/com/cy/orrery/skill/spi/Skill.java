// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.spi;

import java.util.Map;

/**
 * 技能 SPI 接口
 * <p>
 * 所有 Skill 实现此接口以提供统一的执行入口。
 * SkillRuntime 通过 SPI 或 Spring 扫描发现 Skill 实例，
 * 调用 {@link #execute(Map)} 完成技能执行。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public interface Skill {

    /**
     * 获取技能唯一标识
     *
     * @return 技能ID
     */
    String getSkillId();

    /**
     * 获取技能名称
     *
     * @return 技能名称
     */
    String getName();

    /**
     * 获取技能版本
     *
     * @return 版本号
     */
    default String getVersion() {
        return "1.0.0";
    }

    /**
     * 获取技能摘要描述
     *
     * @return 摘要描述
     */
    default String getSummary() {
        return "";
    }

    /**
     * 执行技能
     *
     * @param input 输入参数
     * @return 输出结果
     */
    Map<String, Object> execute(Map<String, Object> input);
}
