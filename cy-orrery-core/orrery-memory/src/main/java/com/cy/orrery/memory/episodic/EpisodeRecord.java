// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.episodic;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 情景卡片（一次会话/run 的经历沉淀："做了什么、怎么做的、结果如何"）。
 *
 * @author pcy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodeRecord {

    /** 情景 ID（= sessionId，同会话一张卡滚动更新）。 */
    private String episodeId;

    /** 会话 ID。 */
    private String sessionId;

    /** 租户 ID（可空）。 */
    private String tenantId;

    /** 归属用户（可空）。 */
    private String userId;

    /** 归属 Agent（可空）。 */
    private String agentId;

    /** 标题（≤20 字，信息密集）。 */
    private String title;

    /** 摘要（≤300 字）。 */
    private String summary;

    /** 结果（success/failure/aborted + 一句话原因）。 */
    private String outcome;

    /** 涉及的关键实体。 */
    private List<String> entities;

    /** 被验证有效或无效的做法。 */
    private List<String> lessons;

    /** 最近更新时刻。 */
    private Instant updatedAt;
}
