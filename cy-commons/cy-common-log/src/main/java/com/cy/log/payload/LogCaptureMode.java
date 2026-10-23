package com.cy.log.payload;

/**
 * 载荷采集模式。
 * <p>
 * 表示日志组件最终对一个载荷采取的采集策略，写入到结构化结果的 {@code capture} 字段，
 * 便于后续对日志进行分级展示与查询。
 * </p>
 *
 * @author pcy
 * @date 2026/04/29
 */
public enum LogCaptureMode {

    /**
     * 完整采集：载荷被完整序列化并保留全部内容。
     */
    FULL,

    /**
     * 预览采集：仅保留前若干字节并标记 {@code truncated=true}。
     */
    PREVIEW,

    /**
     * 仅元信息：不保留内容，仅记录类型 / 大小 / 文件名等元数据。
     */
    METADATA_ONLY,

    /**
     * 跳过采集：明确拒绝采集（不可序列化对象 / 流对象）。
     */
    SKIPPED,

    /**
     * 失败降级：序列化过程中发生异常，仅保留降级摘要。
     */
    FALLBACK
}
