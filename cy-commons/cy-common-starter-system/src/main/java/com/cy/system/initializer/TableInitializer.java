package com.cy.system.initializer;

import com.cy.system.config.SystemProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import javax.sql.DataSource;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 数据库表初始化器，启动时自动创建表结构
 */
@RequiredArgsConstructor
public class TableInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TableInitializer.class);
    private static final String SQL_FILE_PATH = "sql/init_tables.sql";

    private final SystemProperties properties;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isTableInit()) {
            log.info("系统管理模块表初始化已禁用");
            return;
        }
        log.info("开始初始化系统管理模块数据库表...");
        try {
            String sqlContent = loadSqlFile();
            executeSql(sqlContent);
            log.info("系统管理模块数据库表初始化完成");
        } catch (Exception e) {
            log.error("系统管理模块数据库表初始化失败", e);
            throw e;
        }
    }

    /**
     * 从classpath加载SQL文件内容
     */
    private String loadSqlFile() throws Exception {
        ClassPathResource resource = new ClassPathResource(SQL_FILE_PATH);
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }

    /**
     * 执行SQL语句
     */
    private void executeSql(String sqlContent) throws Exception {
        String[] statements = sqlContent.split(";");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                String trimmedSql = sql.trim();
                if (!trimmedSql.isEmpty()) {
                    statement.execute(trimmedSql);
                    String tableName = extractTableName(trimmedSql);
                    if (tableName != null) {
                        log.info("{} 表初始化完成", tableName);
                    }
                }
            }
        }
    }

    /**
     * 从SQL语句中提取表名
     */
    private String extractTableName(String sql) {
        String upperSql = sql.toUpperCase();
        int tableIndex = upperSql.indexOf("TABLE");
        if (tableIndex == -1) {
            return null;
        }
        int existsIndex = upperSql.indexOf("EXISTS", tableIndex);
        int startIndex = existsIndex != -1 ? existsIndex + 6 : tableIndex + 5;
        String remaining = sql.substring(startIndex).trim();
        int endIndex = remaining.indexOf('(');
        if (endIndex == -1) {
            endIndex = remaining.indexOf(' ');
        }
        if (endIndex == -1) {
            return remaining.trim();
        }
        return remaining.substring(0, endIndex).trim();
    }
}
