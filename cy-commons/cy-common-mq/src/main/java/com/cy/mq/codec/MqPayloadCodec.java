package com.cy.mq.codec;

/**
 * Payload 编解码器。
 * <p>
 * 把任意业务对象与原始字节互相转换。本接口是 driver / runtime 之间
 * 处理 payload 的统一抽象，支持 POJO / Map / String / byte[] 等多种类型。
 * </p>
 * <p>
 * 默认实现以 JSON（fastjson2）为主，并对几类特殊类型做直通：
 * </p>
 * <ul>
 *     <li>{@code byte[]}：直接返回字节，不做 JSON 编码 / 解码。</li>
 *     <li>{@code String}：按 UTF-8 直接编码 / 解码，不做 JSON 引号包裹。</li>
 *     <li>{@code Object} / {@link com.alibaba.fastjson2.JSONObject} / {@link com.alibaba.fastjson2.JSONArray} /
 *     {@link java.util.Map}：按 fastjson2 的通用 JSON 模型解析。</li>
 *     <li>POJO：按 fastjson2 反序列化为目标类型。</li>
 * </ul>
 *
 * @author pcy
 */
public interface MqPayloadCodec {

    /**
     * 序列化业务对象为字节。
     * <p>
     * 实现规则：
     * </p>
     * <ul>
     *     <li>{@code null} 返回长度为 0 的字节数组。</li>
     *     <li>{@code byte[]} 直接返回。</li>
     *     <li>{@code String} 按 UTF-8 编码。</li>
     *     <li>其他对象按 JSON 序列化为 UTF-8 字节。</li>
     * </ul>
     *
     * @param payload 业务对象
     * @return 字节内容（永远非 {@code null}）
     */
    byte[] encode(Object payload);

    /**
     * 把字节按目标类型反序列化为业务对象。
     * <p>
     * 实现规则：
     * </p>
     * <ul>
     *     <li>{@code body == null} 或长度为 0：返回类型对应的空值（{@code null} 或空字符串 / 空数组）。</li>
     *     <li>目标类型为 {@code byte[]}：直接返回字节副本。</li>
     *     <li>目标类型为 {@code String}：按 UTF-8 解码。</li>
     *     <li>目标类型为 {@link Object}：返回 fastjson2 通用对象（{@code JSONObject} / {@code JSONArray} / 字符串 / 数字 / 布尔等）。</li>
     *     <li>其他类型：按 JSON 反序列化为目标类型。</li>
     * </ul>
     *
     * @param body        原始字节
     * @param payloadType 目标类型
     * @param <T>         目标类型
     * @return 反序列化结果
     */
    <T> T decode(byte[] body, Class<T> payloadType);
}
