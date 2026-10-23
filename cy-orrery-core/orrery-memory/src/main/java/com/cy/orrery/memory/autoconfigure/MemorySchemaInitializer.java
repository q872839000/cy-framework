// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.autoconfigure;

import com.cy.core.exception.CYException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆表结构初始化（jdbc-auto-init=true 时启动期执行 schema.sql；生产建议手工执行）。
 *
 * @author pcy
 */
public class MemorySchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(MemorySchemaInitializer.class);

    /** schema 资源路径。 */
    private static final String SCHEMA_RESOURCE = "orrery-memory/schema.sql";

    /** JDBC。 */
    private final JdbcTemplate jdbc;

    /**
     * 构造初始化器。
     *
     * @param dataSource 数据源
     */
    public MemorySchemaInitializer(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 执行建表脚本（幂等：IF NOT EXISTS）。 */
    public void init() {
        try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : sql.split(";")) {
                String trimmed = statement.strip();
                if (!trimmed.isEmpty()) {
                    jdbc.execute(trimmed);
                }
            }
            log.info("记忆表结构初始化完成");
        } catch (IOException | RuntimeException e) {
            throw new CYException(50615, e.getMessage());
        }
    }
}
