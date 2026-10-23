package com.cy.log.filter;

import com.cy.log.config.LogProperties;
import com.cy.log.payload.ContentTypes;
import com.cy.log.util.LogUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * 请求体缓存过滤器（有界版本）。
 * <p>
 * 将 {@link HttpServletRequest} 包装为 {@link BoundedContentCachingRequestWrapper}，
 * 使日志组件可以读取部分请求体内容用于审计；同时保证：
 * </p>
 * <ul>
 *     <li><b>有界缓存</b>：最多缓存 {@link LogProperties#getMaxPayloadBytes()} 字节，
 *         超出部分透传不缓存，杜绝 OOM</li>
 *     <li><b>multipart / 二进制不缓存</b>：文件上传与二进制请求只读取元信息，
 *         请求体保持原始流不被复制到内存</li>
 *     <li><b>排除路径跳过</b>：{@code excludePatterns} 命中的请求完全不包装</li>
 *     <li><b>关闭请求体记录时跳过</b>：{@code recordRequestBody=false} 时不进行包装，
 *         彻底消除日志组件对请求流的影响</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Slf4j
public class RequestCachingFilter implements Filter, Ordered {

    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final LogProperties properties;

    public RequestCachingFilter(LogProperties properties) {
        this.properties = properties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        if (!shouldWrap(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        if (httpRequest instanceof BoundedContentCachingRequestWrapper) {
            chain.doFilter(httpRequest, response);
            return;
        }

        int maxBytes = properties.getMaxPayloadBytes() > 0
                ? properties.getMaxPayloadBytes()
                : LogProperties.DEFAULT_MAX_PAYLOAD_BYTES;
        BoundedContentCachingRequestWrapper wrapper = new BoundedContentCachingRequestWrapper(httpRequest, maxBytes);
        chain.doFilter(wrapper, response);
    }

    /**
     * 判断当前请求是否需要进行有界缓存包装。
     * <p>
     * 命中以下任意条件均跳过包装，请求体保持原始流：
     * </p>
     * <ul>
     *     <li>关闭了 {@code cy.log.record-request-body}</li>
     *     <li>URI 命中 {@code excludePatterns}</li>
     *     <li>请求是 multipart 或二进制类型（仅靠元信息记录）</li>
     * </ul>
     * <p>
     * Content-Type 分类与 {@link com.cy.log.payload.LogPayloadProcessor} 保持完全一致，
     * 统一通过 {@link ContentTypes} 实现。
     * </p>
     */
    private boolean shouldWrap(HttpServletRequest request) {
        if (!properties.isRecordRequestBody()) {
            return false;
        }
        if (LogUtils.isExcluded(request.getRequestURI(), properties.getExcludePatterns())) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType == null) {
            return true;
        }
        if (ContentTypes.isMultipart(contentType) || ContentTypes.isBinary(contentType)) {
            return false;
        }
        // 文本类型或未知类型：缓存以便日志组件读取预览
        return true;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
