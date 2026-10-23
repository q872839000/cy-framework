package com.cy.filestore.resource;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文件资源
 * <p>
 * 封装文件输入流及其元数据，配合 try-with-resources 确保资源正确关闭。
 * </p>
 * <pre>{@code
 * try (FileResource resource = storageStrategy.downloadAsResource(fileKey)) {
 *     InputStream stream = resource.getInputStream();
 *     // 使用流...
 * }
 * }</pre>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class FileResource implements Closeable {

    /**
     * 文件唯一标识
     */
    private final String fileKey;

    /**
     * 文件输入流
     */
    private final InputStream inputStream;

    /**
     * 文件大小（字节）
     */
    private final long size;

    /**
     * 文件内容类型（MIME 类型）
     */
    private final String contentType;

    /**
     * 创建文件资源
     *
     * @param fileKey     文件唯一标识
     * @param inputStream 文件输入流
     * @param size        文件大小（字节）
     * @param contentType 文件内容类型
     */
    public FileResource(String fileKey, InputStream inputStream, long size, String contentType) {
        this.fileKey = fileKey;
        this.inputStream = inputStream;
        this.size = size;
        this.contentType = contentType;
    }

    /**
     * 获取文件唯一标识
     *
     * @return 文件唯一标识
     */
    public String getFileKey() {
        return fileKey;
    }

    /**
     * 获取文件输入流
     *
     * @return 文件输入流
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * 获取文件大小
     *
     * @return 文件大小（字节）
     */
    public long getSize() {
        return size;
    }

    /**
     * 获取文件内容类型
     *
     * @return 文件内容类型（MIME 类型）
     */
    public String getContentType() {
        return contentType;
    }

    @Override
    public void close() throws IOException {
        if (inputStream != null) {
            inputStream.close();
        }
    }
}
