package com.cy.filestore.config;

import com.cy.filestore.FileServiceFacade;
import com.cy.filestore.resource.FileResource;
import com.cy.filestore.storage.FileStorageStrategy;
import com.cy.filestore.vo.FileInfo;
import com.cy.filestore.vo.FileUploadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStoreAutoConfigurationTest {

    @TempDir
    Path tempDir;

    /**
     * 构建上下文运行器，指定本地存储根目录为临时目录
     */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FileStoreAutoConfiguration.class))
                .withPropertyValues("cy.filestore.local.base-path=" + tempDir.toString().replace('\\', '/'));
    }

    @Test
    void shouldCreateLocalStrategyAndFacadeByDefault() {
        contextRunner().run(context -> {
            FileStorageStrategy strategy = context.getBean(FileStorageStrategy.class);
            assertEquals("local", strategy.getStorageType());

            FileServiceFacade facade = context.getBean(FileServiceFacade.class);
            assertNotNull(facade);
            assertEquals("local", facade.getStorageType());
        });
    }

    @Test
    void shouldFailFastWhenTypeUnsupported() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FileStoreAutoConfiguration.class))
                .withPropertyValues("cy.filestore.type=unknown")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void shouldBackOffWhenUserProvidesStrategy() {
        contextRunner()
                .withBean(FileStorageStrategy.class, FileStorageStrategyStub::new)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(FileStorageStrategy.class).size());
                    assertEquals("stub", context.getBean(FileServiceFacade.class).getStorageType());
                });
    }

    /**
     * 自定义存储策略桩实现
     */
    static class FileStorageStrategyStub implements FileStorageStrategy {

        @Override
        public FileInfo upload(FileUploadRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileResource downloadAsResource(String fileKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void downloadToFile(String fileKey, Path targetPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileInfo stat(String fileKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(String fileKey) {
            return false;
        }

        @Override
        public boolean exists(String fileKey) {
            return false;
        }

        @Override
        public String getStorageType() {
            return "stub";
        }
    }
}
