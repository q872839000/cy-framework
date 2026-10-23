package com.cy.auth.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 网关签名工具
 * <p>
 * 使用 HMAC-SHA256 对网关转发请求进行签名，防止外部客户端伪造 X-Gateway-Forward 头。
 * 网关端生成签名，下游服务端验证签名。双方使用相同的共享密钥。
 * </p>
 * <p>
 * 签名算法：HMAC-SHA256(secret, requestPath + ":" + timestamp)
 * </p>
 *
 * @author pcy
 * @date 2026/03/19
 */
public final class GatewaySignatureUtil {

    private static final String ALGORITHM = "HmacSHA256";

    private GatewaySignatureUtil() {
    }

    /**
     * 生成签名
     *
     * @param secret      共享密钥
     * @param requestPath 请求路径
     * @param timestamp   时间戳（毫秒）
     * @return 十六进制签名字符串
     */
    public static String sign(String secret, String requestPath, long timestamp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String payload = requestPath + ":" + timestamp;
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    /**
     * 验证签名
     *
     * @param secret        共享密钥
     * @param requestPath   请求路径
     * @param timestamp     时间戳（毫秒）
     * @param signature     待验证的签名
     * @param toleranceMs   时间戳容忍窗口（毫秒）
     * @return 验证是否通过
     */
    public static boolean verify(String secret, String requestPath,
                                  long timestamp, String signature, long toleranceMs) {
        // 防重放：检查时间戳是否在容忍窗口内
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > toleranceMs) {
            return false;
        }

        String expected = sign(secret, requestPath, timestamp);
        return expected.equals(signature);
    }
}
