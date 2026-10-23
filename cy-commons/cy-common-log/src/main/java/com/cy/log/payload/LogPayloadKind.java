package com.cy.log.payload;

/**
 * 载荷类型分类。
 * <p>
 * 日志组件根据该分类决定如何采集与展示一个对象：
 * </p>
 * <ul>
 *     <li>{@link #NULL}：空值</li>
 *     <li>{@link #SCALAR}：基本类型 / 字符串 / 数字 / 布尔等可直接序列化的小值</li>
 *     <li>{@link #TEXT}：文本载荷（字符串、HTTP body 文本）</li>
 *     <li>{@link #OBJECT}：普通 POJO / Map / 集合，可结构化序列化</li>
 *     <li>{@link #BINARY}：byte[]、ByteBuffer、字节数组流等二进制载荷</li>
 *     <li>{@link #FILE}：单个文件载荷（{@code MultipartFile}、{@code File}、{@code Resource}）</li>
 *     <li>{@link #MULTIPART}：multipart 请求 / 文件数组 / 文件 Map 等多文件载荷</li>
 *     <li>{@link #STREAM}：流对象（不可重复读取，禁止从中读取内容）</li>
 *     <li>{@link #UNSUPPORTED}：明确不支持序列化的对象（HttpServletRequest 等）</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/29
 */
public enum LogPayloadKind {

    /**
     * 空值。
     */
    NULL,

    /**
     * 基本类型 / 字符串 / 数字 / 布尔等。
     */
    SCALAR,

    /**
     * 文本载荷。
     */
    TEXT,

    /**
     * 普通对象。
     */
    OBJECT,

    /**
     * 二进制载荷。
     */
    BINARY,

    /**
     * 单个文件载荷。
     */
    FILE,

    /**
     * 多文件 / multipart 载荷。
     */
    MULTIPART,

    /**
     * 流对象，禁止读取内容。
     */
    STREAM,

    /**
     * 不支持的对象类型。
     */
    UNSUPPORTED
}
