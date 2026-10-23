// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint;

import com.cy.orrery.agent.runtime.checkpoint.impl.JacksonCheckpointSerializer;

/**
 * 检查点序列化 SPI：Checkpoint ↔ 存储载荷的编解码边界。
 * <p>
 * 默认实现 {@link JacksonCheckpointSerializer}（JSON 文本，供 Redis 等
 * 字符串 / 字节存储使用）。宿主自定义属性类型（领域状态对象）的强类型
 * 往返经键类型注册表扩展；未注册的类型按通用 JSON 结构（Map / List /
 * 标量）还原，宿主恢复后需自行后处理。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public interface CheckpointSerializer {

    /**
     * 序列化检查点为文本载荷。
     *
     * @param checkpoint 检查点
     * @return 文本载荷
     */
    String serialize(Checkpoint checkpoint);

    /**
     * 反序列化文本载荷为检查点。
     *
     * @param payload 文本载荷
     * @return 检查点
     */
    Checkpoint deserialize(String payload);
}
