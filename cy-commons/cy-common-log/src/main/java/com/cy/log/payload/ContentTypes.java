package com.cy.log.payload;

import java.util.Locale;

/**
 * HTTP {@code Content-Type} 分类工具。
 * <p>
 * 在 {@code cy-common-log} 内统一识别请求体类型，避免在多处（请求体缓存过滤器 / 载荷处理器 / 切面）
 * 重复实现相似的 {@code Content-Type} 字符串前缀判断，导致行为不一致或维护成本扩散。
 * </p>
 *
 * <h3>分类语义</h3>
 * <ul>
 *     <li>{@link #isMultipart(String)}：以 {@code multipart/} 开头，常见于文件上传。</li>
 *     <li>{@link #isText(String)}：可被人读懂的纯文本载荷（{@code text/*}、{@code application/json} 等及结构化 JSON/XML 后缀）。</li>
 *     <li>{@link #isBinary(String)}：明确为二进制载荷（{@code image/*}、{@code audio/*}、{@code video/*}、二进制 {@code application/*} 等）。</li>
 * </ul>
 * <p>
 * 三个判断互斥：同一 {@code Content-Type} 至多命中其中一个分类。{@link #isText(String)} 优先级高于 {@link #isBinary(String)}，
 * 因此带有 {@code +json} / {@code +xml} 后缀的 {@code application/...} 也会被识别为文本。
 * </p>
 *
 * @author pcy
 * @date 2026/04/29
 */
public final class ContentTypes {

    private ContentTypes() {
    }

    /**
     * 判断 {@code Content-Type} 是否为 multipart 类型。
     */
    public static boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    /**
     * 判断 {@code Content-Type} 是否为可读文本类型（含结构化 JSON/XML）。
     */
    public static boolean isText(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
                || lower.startsWith("application/json")
                || lower.startsWith("application/xml")
                || lower.startsWith("application/x-www-form-urlencoded")
                || lower.startsWith("application/javascript")
                || lower.startsWith("application/graphql")
                || lower.contains("+json")
                || lower.contains("+xml");
    }

    /**
     * 判断 {@code Content-Type} 是否为二进制载荷。
     * <p>
     * 当 {@link #isText(String)} 已返回 {@code true} 时，本方法一定返回 {@code false}，确保两个分类互斥。
     * </p>
     */
    public static boolean isBinary(String contentType) {
        if (contentType == null) {
            return false;
        }
        if (isText(contentType)) {
            return false;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("application/")
                || lower.startsWith("image/")
                || lower.startsWith("audio/")
                || lower.startsWith("video/");
    }
}
