package com.cy.log.payload;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件载荷描述器。
 * <p>
 * 负责识别一个对象是否为“文件 / multipart / 二进制 / 流”载荷，并提取仅元信息（文件名、大小、类型、数量）。
 * 这是日志组件“记录文件清单而非文件内容”的关键支撑：
 * </p>
 * <ul>
 *     <li>{@link MultipartFile} → {@link FileSummary}</li>
 *     <li>{@link MultipartFile}[] / Collection → 多文件 manifest</li>
 *     <li>{@link MultipartRequest} → file map manifest</li>
 *     <li>{@link File}、{@link Resource} → 单文件 summary</li>
 *     <li>{@code byte[]} / {@code ByteBuffer} → BINARY 摘要</li>
 *     <li>{@link java.io.InputStream} 等 → STREAM 摘要</li>
 * </ul>
 * <p>
 * <b>不会读取文件内容</b>，仅访问文件名 / 大小 / contentType 等元信息。
 * </p>
 *
 * @author pcy
 * @date 2026/04/29
 */
public final class FilePayloadDescriber {

    private FilePayloadDescriber() {
    }

    /**
     * 判断对象是否为单文件载荷。
     */
    public static boolean isSingleFile(Object value) {
        return value instanceof MultipartFile
                || value instanceof File
                || value instanceof Resource;
    }

    /**
     * 判断对象是否为 multipart / 多文件载荷。
     */
    public static boolean isMultiFile(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof MultipartRequest) {
            return true;
        }
        if (value instanceof MultipartFile[]) {
            return true;
        }
        if (value instanceof Collection<?> collection && !collection.isEmpty()) {
            for (Object element : collection) {
                if (element != null) {
                    return element instanceof MultipartFile;
                }
            }
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Object element : map.values()) {
                if (element != null) {
                    return element instanceof MultipartFile;
                }
            }
        }
        return false;
    }

    /**
     * 判断对象是否为二进制载荷。
     */
    public static boolean isBinary(Object value) {
        return value instanceof byte[] || value instanceof java.nio.ByteBuffer;
    }

    /**
     * 判断对象是否为流载荷。
     */
    public static boolean isStream(Object value) {
        if (value == null) {
            return false;
        }
        Class<?> type = value.getClass();
        return java.io.InputStream.class.isAssignableFrom(type)
                || java.io.OutputStream.class.isAssignableFrom(type)
                || java.io.Reader.class.isAssignableFrom(type)
                || java.io.Writer.class.isAssignableFrom(type)
                || isAssignable(type, "org.springframework.web.servlet.mvc.method.annotation.SseEmitter")
                || isAssignable(type, "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter")
                || isAssignable(type, "org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody")
                || isAssignable(type, "org.reactivestreams.Publisher");
    }

    /**
     * 提取单文件元信息。
     */
    public static FileSummary describeSingleFile(Object value) {
        if (value instanceof MultipartFile mf) {
            return new FileSummary(null, mf.getOriginalFilename(), mf.getContentType(), mf.getSize());
        }
        if (value instanceof File file) {
            return new FileSummary(null, file.getName(), null, safeLength(file));
        }
        if (value instanceof Resource resource) {
            String name;
            try {
                name = resource.getFilename();
            } catch (Exception e) {
                name = resource.getDescription();
            }
            long size;
            try {
                size = resource.contentLength();
            } catch (Exception e) {
                size = -1;
            }
            return new FileSummary(null, name, null, size);
        }
        return null;
    }

    /**
     * 提取多文件 / multipart 元信息。
     */
    public static MultipartManifest describeMultipart(Object value) {
        List<FileSummary> files = new ArrayList<>();
        if (value instanceof MultipartRequest multipart) {
            Map<String, MultipartFile> map = multipart.getFileMap();
            if (map != null) {
                for (Map.Entry<String, MultipartFile> entry : map.entrySet()) {
                    MultipartFile file = entry.getValue();
                    if (file == null) {
                        continue;
                    }
                    files.add(new FileSummary(entry.getKey(), file.getOriginalFilename(),
                            file.getContentType(), file.getSize()));
                }
            }
        } else if (value instanceof MultipartFile[] arr) {
            for (MultipartFile file : arr) {
                if (file == null) {
                    continue;
                }
                files.add(new FileSummary(null, file.getOriginalFilename(),
                        file.getContentType(), file.getSize()));
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                if (element instanceof MultipartFile file) {
                    files.add(new FileSummary(null, file.getOriginalFilename(),
                            file.getContentType(), file.getSize()));
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof MultipartFile file) {
                    String fieldName = entry.getKey() == null ? null : entry.getKey().toString();
                    files.add(new FileSummary(fieldName, file.getOriginalFilename(),
                            file.getContentType(), file.getSize()));
                }
            }
        }
        long totalSize = files.stream().mapToLong(f -> Math.max(0, f.size)).sum();
        return new MultipartManifest(files.size(), totalSize, files);
    }

    /**
     * 提取二进制载荷大小。
     */
    public static long binarySize(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        if (value instanceof java.nio.ByteBuffer buffer) {
            return buffer.remaining();
        }
        return -1;
    }

    /**
     * 将文件 / multipart / 二进制 / 流摘要转为可序列化的 Map（结构化日志载荷）。
     * <p>
     * 输出形如：
     * </p>
     * <pre>
     * {
     *   "kind": "MULTIPART",
     *   "capture": "METADATA_ONLY",
     *   "fileCount": 2,
     *   "totalSize": 31457280,
     *   "files": [
     *     {"fieldName":"files","filename":"a.pdf","contentType":"application/pdf","size":10485760}
     *   ]
     * }
     * </pre>
     */
    public static Map<String, Object> toMetadataMap(LogPayloadKind kind, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind.name());
        map.put("capture", LogCaptureMode.METADATA_ONLY.name());
        switch (kind) {
            case FILE -> {
                FileSummary summary = describeSingleFile(value);
                if (summary != null) {
                    map.put("file", summary.toMap());
                }
            }
            case MULTIPART -> {
                MultipartManifest manifest = describeMultipart(value);
                map.put("fileCount", manifest.fileCount);
                map.put("totalSize", manifest.totalSize);
                List<Map<String, Object>> fileList = new ArrayList<>(manifest.files.size());
                for (FileSummary file : manifest.files) {
                    fileList.add(file.toMap());
                }
                map.put("files", fileList);
            }
            case BINARY -> {
                long size = binarySize(value);
                map.put("type", value == null ? null : value.getClass().getSimpleName());
                map.put("size", size);
            }
            case STREAM -> {
                map.put("type", value == null ? null : value.getClass().getName());
                map.put("read", false);
            }
            default -> {
                // 其他类型不会进入该方法
            }
        }
        return map;
    }

    private static long safeLength(File file) {
        try {
            return file.length();
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isAssignable(Class<?> type, String className) {
        try {
            Class<?> target = Class.forName(className, false, FilePayloadDescriber.class.getClassLoader());
            return target.isAssignableFrom(type);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 单文件元信息摘要。
     */
    public static final class FileSummary {

        public final String fieldName;
        public final String filename;
        public final String contentType;
        public final long size;

        public FileSummary(String fieldName, String filename, String contentType, long size) {
            this.fieldName = fieldName;
            this.filename = filename;
            this.contentType = contentType;
            this.size = size;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (fieldName != null) {
                map.put("fieldName", fieldName);
            }
            map.put("filename", filename);
            map.put("contentType", contentType);
            map.put("size", size);
            return map;
        }
    }

    /**
     * 多文件清单。
     */
    public static final class MultipartManifest {

        public final int fileCount;
        public final long totalSize;
        public final List<FileSummary> files;

        public MultipartManifest(int fileCount, long totalSize, List<FileSummary> files) {
            this.fileCount = fileCount;
            this.totalSize = totalSize;
            this.files = files;
        }
    }
}
