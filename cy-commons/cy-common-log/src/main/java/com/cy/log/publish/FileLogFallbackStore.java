package com.cy.log.publish;

import com.alibaba.fastjson2.JSON;
import com.cy.log.config.LogProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于文件的日志兜底存储。
 *
 * @author pcy
 * @date 2026/04/14
 */
@Slf4j
public class FileLogFallbackStore implements LogFallbackStore {

    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 兜底配置。
     */
    private final LogProperties.Fallback fallback;

    /**
     * 兜底目录。
     */
    private final Path directory;

    public FileLogFallbackStore(LogProperties.Fallback fallback) {
        this.fallback = fallback;
        String directoryValue = fallback != null && StringUtils.hasText(fallback.getDirectory())
                ? fallback.getDirectory()
                : "logs/cy-log-fallback";
        this.directory = Path.of(directoryValue);
    }

    @Override
    public synchronized boolean store(LogPublishTask task, String reason, Exception exception) {
        if (fallback == null || !fallback.isEnabled() || task == null || task.getRecord() == null) {
            return false;
        }

        try {
            Files.createDirectories(directory);
            pruneOldFilesIfNeeded();

            Path file = resolveActiveFile(task.getLane());
            FallbackEntry entry = new FallbackEntry();
            entry.setLane(task.getLane().name());
            entry.setTopic(task.getTopic());
            entry.setReason(reason);
            entry.setTimestamp(System.currentTimeMillis());
            entry.setExceptionClass(exception != null ? exception.getClass().getName() : null);
            entry.setExceptionMessage(exception != null ? exception.getMessage() : null);
            entry.setRecord(task.getRecord());

            String line = JSON.toJSONString(entry) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            log.warn("日志本地兜底写入失败: lane={}, topic={}, reason={}",
                    task.getLane(), task.getTopic(), reason, e);
            return false;
        }
    }

    /**
     * 解析当前活动文件。
     */
    private Path resolveActiveFile(LogLane lane) throws IOException {
        String prefix = "cy-log-" + lane.metricKey();
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted()
                    .toList();
        }

        if (!files.isEmpty()) {
            Path latest = files.get(files.size() - 1);
            long size = Files.size(latest);
            if (size < Math.max(1L, fallback.getMaxFileSizeBytes())) {
                return latest;
            }
        }

        String fileName = prefix + "-" + FILE_TIME_FORMATTER.format(LocalDateTime.now()) + ".spool";
        return directory.resolve(fileName);
    }

    /**
     * 清理超过数量上限的旧文件。
     */
    private void pruneOldFilesIfNeeded() throws IOException {
        int maxFiles = Math.max(1, fallback.getMaxFiles());
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        int overflow = files.size() - maxFiles + 1;
        for (int i = 0; i < overflow; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    /**
     * 兜底条目。
     */
    @Data
    private static class FallbackEntry {

        private String lane;

        private String topic;

        private String reason;

        private Long timestamp;

        private String exceptionClass;

        private String exceptionMessage;

        private Object record;
    }
}
