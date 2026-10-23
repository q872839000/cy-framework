package com.cy.log.persistence.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用链路详情视图对象
 * <p>
 * 结构化的调用链路数据，专为前端图形化渲染设计：
 * <ul>
 *     <li><b>spanTree</b> — 树状方法调用链，支持缩进列表 / 树形组件渲染</li>
 *     <li><b>SpanNode.offsetFromTraceStart + duration</b> — 瀑布流时间轴（横向条形图）</li>
 *     <li><b>SpanNode.inputArgs / returnValue</b> — 点击节点查看详情</li>
 *     <li><b>summary</b> — 顶部统计概览（总 span 数、失败数、最大深度、瓶颈方法）</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/09
 */
@Data
public class TraceDetailVO {

    // ==================== 链路概览 ====================

    /**
     * 链路追踪 ID
     */
    private String traceId;

    /**
     * 链路开始时间（毫秒时间戳，取所有 span 中最早的 startTime）
     */
    private long startTime;

    /**
     * 链路总耗时（毫秒，取根 span 的 duration）
     */
    private long totalDuration;

    /**
     * 链路是否成功（所有 span 均成功时为 true）
     */
    private boolean success;

    /**
     * 入口服务名
     */
    private String serviceName;

    /**
     * 入口请求 URI
     */
    private String requestUri;

    /**
     * 入口请求方法（GET/POST 等）
     */
    private String requestMethod;

    /**
     * 客户端 IP
     */
    private String clientIp;

    /**
     * 操作用户信息
     */
    private UserInfo user;

    /**
     * 链路统计摘要
     */
    private TraceSummary summary;

    /**
     * 调用链路 span 树（根节点列表，每个根节点通过 children 递归嵌套子 span）
     * <p>
     * 查询侧优先按 {@code parentSpanId -> spanId} 的真实 span 身份关系建树；
     * 若数据本身不具备稳定的 span 身份关系，则自动回退到基于 depth 的保底建树逻辑。
     */
    private List<SpanNode> spanTree;

    // ==================== 内部 VO ====================

    /**
     * 用户信息
     */
    @Data
    public static class UserInfo {
        private Long userId;
        private String username;
        private String nickname;
    }

    /**
     * 链路统计摘要
     */
    @Data
    public static class TraceSummary {
        /**
         * ACCESS span 总数
         */
        private int totalSpans;
        /**
         * 涉及的服务数量
         */
        private int totalServices;
        /**
         * 失败 span 数
         */
        private int failedSpans;
        /**
         * 最大调用深度
         */
        private int maxDepth;
        /**
         * 耗时最长的方法（className.methodName）
         */
        private String bottleneckMethod;
        /**
         * 耗时最长的方法的 actualDuration（毫秒）
         */
        private long bottleneckDuration;
    }

    /**
     * 调用链路 span 节点
     * <p>
     * 既是树节点（通过 children 递归），又包含瀑布流渲染所需的时间偏移数据。
     */
    @Data
    public static class SpanNode {
        /**
         * 日志记录 ID（唯一标识，可用作前端 key）
         */
        private Long id;
        /**
         * 所属服务名
         */
        private String serviceName;
        /**
         * 调用类名（简单名）
         */
        private String className;
        /**
         * 调用方法名
         */
        private String methodName;
        /**
         * 调用栈深度（1 = 入口）
         */
        private int depth;
        /**
         * 调用开始时间（毫秒时间戳）
         */
        private long startTime;
        /**
         * 相对于链路起始时间的偏移量（毫秒）
         * <p>
         * 前端用此值在瀑布流时间轴上定位横向位置，无需额外计算
         */
        private long offsetFromTraceStart;
        /**
         * 总耗时（毫秒）
         */
        private long duration;
        /**
         * 实际执行耗时 = 总耗时 - 子方法耗时（毫秒）
         * <p>
         * 用于识别性能瓶颈：actualDuration 高说明本方法自身耗时长
         */
        private long actualDuration;
        /**
         * 方法入参（JSON 字符串，可能已脱敏/截断）
         */
        private String inputArgs;
        /**
         * 方法返回值（JSON 字符串，可能已脱敏/截断）
         */
        private String returnValue;
        /**
         * 是否成功
         */
        private boolean success;
        /**
         * 错误消息
         */
        private String errorMessage;
        /**
         * 业务模块（来自 @AccessIntercept.module）
         */
        private String module;
        /**
         * 操作描述（来自 @AccessIntercept.operationDescribe）
         */
        private String description;
        /**
         * 子 span 列表（递归嵌套）
         */
        private List<SpanNode> children = new ArrayList<>();
    }
}
