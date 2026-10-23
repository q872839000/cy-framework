package com.cy.filestore.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileKeyUtilsTest {

    @Test
    void shouldGenerateFileKeyWithDateDirectoryWhenPrefixMissing() {
        String fileKey = FileKeyUtils.generateFileKey(null, "report.pdf");

        assertTrue(fileKey.matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.pdf"));
    }

    @Test
    void shouldGenerateFileKeyUnderGivenPrefix() {
        String fileKey = FileKeyUtils.generateFileKey("user/avatar", "photo.jpg");

        assertTrue(fileKey.startsWith("user/avatar/"));
        assertTrue(fileKey.endsWith(".jpg"));
    }

    @Test
    void shouldGenerateFileKeyWithoutExtensionWhenFilenameHasNone() {
        String fileKey = FileKeyUtils.generateFileKey("docs", "README");

        assertTrue(fileKey.matches("docs/[0-9a-f]{32}"));
    }

    @Test
    void shouldNormalizeRelativePath() {
        assertEquals("a/b/c", FileKeyUtils.normalizeRelativePath("\\a//b/c/"));
        assertEquals("a/b", FileKeyUtils.normalizeRelativePath("a/./b"));
        assertEquals("", FileKeyUtils.normalizeRelativePath("  "));
    }

    @Test
    void shouldRejectParentDirectoryTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> FileKeyUtils.normalizeRelativePath("../etc/passwd"));
    }
}
