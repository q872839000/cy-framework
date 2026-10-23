package com.cy.filestore.spi;

import com.cy.core.exception.CYException;
import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.storage.FileStorageStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStorageStrategyFactoryTest {

    @Test
    void shouldSupportBuiltInLocalType() {
        assertTrue(FileStorageStrategyFactory.isSupported("local"));
        assertFalse(FileStorageStrategyFactory.isSupported("unknown"));
    }

    @Test
    void shouldCreateLocalStrategy() {
        FileStorageStrategy strategy = FileStorageStrategyFactory.create("local", new FileStoreProperties());

        assertEquals("local", strategy.getStorageType());
    }

    @Test
    void shouldThrowWhenTypeUnsupported() {
        CYException exception = assertThrows(CYException.class,
                () -> FileStorageStrategyFactory.create("unknown", new FileStoreProperties()));

        assertEquals(32005, exception.getErrorCode());
    }
}
