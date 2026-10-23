package com.cy.mq.codec;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * 基于 fastjson2 的默认 {@link MqPayloadCodec} 实现。
 * <p>
 * 字节级 codec：
 * </p>
 * <ul>
 *     <li>{@code byte[]}：直通。</li>
 *     <li>{@code String}：UTF-8 直通，不做 JSON 引号包裹。</li>
 *     <li>{@link Object} / {@link JSONObject} / {@link JSONArray} / {@link java.util.Map}：fastjson2 通用 JSON 解析。</li>
 *     <li>POJO：fastjson2 反序列化。</li>
 * </ul>
 * <p>
 * 设计上面向「外部系统也可能发送任意 JSON / 文本 / 二进制」场景，
 * 因此对每类目标都给出确定行为，不做猜测式协议识别。
 * </p>
 *
 * @author pcy
 */
@Slf4j
public class Fastjson2PayloadCodec implements MqPayloadCodec {

    /**
     * 长度为 0 的字节常量，用于 null payload 的编码结果。
     */
    private static final byte[] EMPTY_BYTES = new byte[0];

    @Override
    public byte[] encode(Object payload) {
        if (payload == null) {
            return EMPTY_BYTES;
        }
        if (payload instanceof byte[] bytes) {
            // 直通：避免对二进制 payload 再做 JSON 转义。
            return bytes;
        }
        if (payload instanceof CharSequence cs) {
            // String / StringBuilder 等纯文本统一按 UTF-8 直通，便于跨语言外部消费方解析。
            return cs.toString().getBytes(StandardCharsets.UTF_8);
        }
        // 其他对象按 JSON 序列化为 UTF-8 字节。
        return JSON.toJSONBytes(payload);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T decode(byte[] body, Class<T> payloadType) {
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType must not be null");
        }
        if (body == null || body.length == 0) {
            // 空 payload 时返回类型对应的安全空值。
            if (payloadType == String.class) {
                return (T) "";
            }
            if (payloadType == byte[].class) {
                return (T) EMPTY_BYTES;
            }
            return null;
        }

        // byte[] 直通，避免任何隐式编码。
        if (payloadType == byte[].class) {
            byte[] copy = new byte[body.length];
            System.arraycopy(body, 0, copy, 0, body.length);
            return (T) copy;
        }

        // String 按 UTF-8 直通，不做 JSON 解析；这样外部系统发送的纯文本可以原样消费。
        if (payloadType == String.class || CharSequence.class.isAssignableFrom(payloadType)) {
            return (T) new String(body, StandardCharsets.UTF_8);
        }

        // Object：返回 fastjson2 通用 JSON 模型。
        if (payloadType == Object.class) {
            return (T) JSON.parse(body);
        }

        // 其他类型按 JSON 反序列化。
        return JSON.parseObject(body, payloadType);
    }
}
