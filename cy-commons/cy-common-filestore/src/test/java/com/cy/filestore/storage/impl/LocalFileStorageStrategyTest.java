package com.cy.filestore.storage.impl;

import com.cy.core.exception.CYException;
import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.resource.FileResource;
import com.cy.filestore.vo.FileInfo;
import com.cy.filestore.vo.FileUploadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileStorageStrategyTest {

    @TempDir
    Path tempDir;

    private LocalFileStorageStrategy createStrategy() {
        FileStoreProperties properties = new FileStoreProperties();
        properties.getLocal().setBasePath(tempDir.toString());
        return new LocalFileStorageStrategy(properties);
    }

    private FileUploadRequest buildRequest(String fileKey, String filename, byte[] content) {
        return FileUploadRequest.builder()
                .fileKey(fileKey)
                .originalFilename(filename)
                .contentType("text/plain")
                .size(content.length)
                .inputStream(new ByteArrayInputStream(content))
                .build();
    }

    @Test
    void shouldUploadThenDownloadAndDelete() throws Exception {
        LocalFileStorageStrategy strategy = createStrategy();
        byte[] content = "hello filestore".getBytes(StandardCharsets.UTF_8);

        FileInfo info = strategy.upload(buildRequest("demo", "a.txt", content));

        assertNotNull(info.getFileKey());
        assertTrue(info.getFileKey().startsWith("demo/"));
        assertTrue(info.getFileKey().endsWith(".txt"));
        assertEquals(content.length, info.getSize());
        assertEquals("a.txt", info.getOriginalFilename());
        assertEquals("local", strategy.getStorageType());
        assertTrue(strategy.exists(info.getFileKey()));

        try (FileResource resource = strategy.downloadAsResource(info.getFileKey())) {
            assertEquals(content.length, resource.getSize());
            assertArrayEquals(content, resource.getInputStream().readAllBytes());
        }

        Path target = tempDir.resolve("copy/a.txt");
        strategy.downloadToFile(info.getFileKey(), target);
        assertArrayEquals(content, Files.readAllBytes(target));

        assertTrue(strategy.delete(info.getFileKey()));
        assertFalse(strategy.exists(info.getFileKey()));
    }

    @Test
    void shouldReturnFileMetadataViaStat() {
        LocalFileStorageStrategy strategy = createStrategy();
        byte[] content = "stat me".getBytes(StandardCharsets.UTF_8);
        FileInfo info = strategy.upload(buildRequest("meta", "b.txt", content));

        FileInfo stat = strategy.stat(info.getFileKey());

        assertEquals(info.getFileKey(), stat.getFileKey());
        assertEquals(content.length, stat.getSize());
        assertNotNull(stat.getContentType());
    }

    @Test
    void shouldThrowWhenFileNotFound() {
        LocalFileStorageStrategy strategy = createStrategy();

        CYException exception = assertThrows(CYException.class,
                () -> strategy.downloadAsResource("missing/file.txt"));
        assertEquals(32003, exception.getErrorCode());

        assertThrows(CYException.class, () -> strategy.stat("missing/file.txt"));
    }

    @Test
    void shouldTreatDeletingMissingFileAsSuccess() {
        LocalFileStorageStrategy strategy = createStrategy();

        assertTrue(strategy.delete("missing/file.txt"));
    }

    @Test
    void shouldRejectPathTraversal() {
        LocalFileStorageStrategy strategy = createStrategy();
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> strategy.upload(buildRequest("../outside", "a.txt", content)));
        assertFalse(strategy.exists("../outside/a.txt"));
    }

    @Test
    void shouldRejectInvalidUploadRequest() {
        LocalFileStorageStrategy strategy = createStrategy();

        assertThrows(IllegalArgumentException.class, () -> strategy.upload(null));
        assertThrows(IllegalArgumentException.class,
                () -> strategy.upload(FileUploadRequest.builder().originalFilename("a.txt").build()));
    }
}
