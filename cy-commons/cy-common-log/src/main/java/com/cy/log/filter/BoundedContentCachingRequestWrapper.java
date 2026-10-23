package com.cy.log.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 有界请求体缓存包装器。
 * <p>
 * 与 Spring 默认的 {@code ContentCachingRequestWrapper} 不同，本包装器会在缓存请求体时
 * 设置一个最大字节上限：
 * </p>
 * <ul>
 *     <li>{@link #getInputStream()} 透明读取原始字节，业务读取行为不受影响</li>
 *     <li>同时把前 {@code maxBytes} 字节复制到 {@link #cachedBuffer} 供日志组件使用</li>
 *     <li>超过 {@code maxBytes} 后剩余字节直接透传，不再写入缓存，杜绝 OOM</li>
 *     <li>通过 {@link #isTruncated()} 暴露是否被截断</li>
 * </ul>
 * <p>
 * 不会 swallow 业务读取异常，也不会重复读取流；缓存仅用于日志组件后续按 {@code byte[]} 输出预览。
 * </p>
 *
 * @author pcy
 * @date 2026/04/29
 */
public class BoundedContentCachingRequestWrapper extends HttpServletRequestWrapper {

    /**
     * {@link ByteArrayOutputStream} 内部缓冲区初始容量上限，避免一开始就分配过大数组。
     */
    private static final int INITIAL_BUFFER_CAPACITY = 8 * 1024;

    private final int maxBytes;
    private final ByteArrayOutputStream cachedBuffer;
    private boolean truncated;
    private long observedBytes;
    private CachedServletInputStream cachedInputStream;

    public BoundedContentCachingRequestWrapper(HttpServletRequest request, int maxBytes) {
        super(request);
        this.maxBytes = Math.max(0, maxBytes);
        // 容量上限对齐 maxBytes，避免无界增长
        this.cachedBuffer = new ByteArrayOutputStream(Math.min(this.maxBytes, INITIAL_BUFFER_CAPACITY));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (cachedInputStream == null) {
            cachedInputStream = new CachedServletInputStream(super.getInputStream());
        }
        return cachedInputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        Charset charset = getCharacterEncoding() != null
                ? Charset.forName(getCharacterEncoding())
                : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    /**
     * 返回已经缓存到上限内的请求体字节。
     */
    public byte[] getContentAsByteArray() {
        return cachedBuffer.toByteArray();
    }

    /**
     * 返回是否触发了截断（原始请求体超过 maxBytes）。
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 返回已经观察到的总字节数（包括未缓存部分）。
     */
    public long getObservedBytes() {
        return observedBytes;
    }

    /**
     * 内部 Servlet 输入流：透传读取的同时按上限缓存。
     */
    private final class CachedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;

        private CachedServletInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int data = delegate.read();
            if (data != -1) {
                observedBytes++;
                if (cachedBuffer.size() < maxBytes) {
                    cachedBuffer.write(data);
                } else {
                    truncated = true;
                }
            }
            return data;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                observedBytes += read;
                int remaining = maxBytes - cachedBuffer.size();
                if (remaining > 0) {
                    int writeLen = Math.min(remaining, read);
                    cachedBuffer.write(b, off, writeLen);
                    if (writeLen < read) {
                        truncated = true;
                    }
                } else if (read > 0) {
                    truncated = true;
                }
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
