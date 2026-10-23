package com.cy.log.persistence.mapper;

import com.cy.log.persistence.entity.LogEntity;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 日志 Mapper。
 *
 * @author pcy
 * @date 2026/01/29
 */
@Mapper
public interface LogMapper extends BaseMapper<LogEntity> {

    /**
     * 查询表是否存在。
     *
     * @param tableName 表名
     * @return 表名，表不存在时返回 null
     */
    @Select("SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = DATABASE() AND table_name = #{tableName}")
    String checkTableExists(@Param("tableName") String tableName);

    /**
     * 查询表记录数。
     *
     * @param tableName 表名
     * @return 记录数
     */
    @Select("SELECT COUNT(*) FROM ${tableName}")
    long countByTable(@Param("tableName") String tableName);

    /**
     * 查询指定前缀的所有表。
     *
     * @param tablePrefix 表名前缀
     * @return 表名列表
     */
    @Select("SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = DATABASE() AND table_name LIKE CONCAT(#{tablePrefix}, '%') " +
            "ORDER BY table_name")
    List<String> findTablesByPrefix(@Param("tablePrefix") String tablePrefix);

    /**
     * 创建日志表。
     *
     * @param tableName 表名
     */
    @Update("""
            CREATE TABLE IF NOT EXISTS ${tableName} (
                id              BIGINT PRIMARY KEY COMMENT '日志ID',
                trace_id        VARCHAR(64) NOT NULL COMMENT '链路追踪ID',
                span_id         VARCHAR(64) COMMENT '当前日志所属的 Span ID',
                source          VARCHAR(32) COMMENT '请求来源',
                log_type        VARCHAR(16) NOT NULL COMMENT '日志类型',
                service_name    VARCHAR(64) COMMENT '服务名称',
                server_ip       VARCHAR(64) COMMENT '服务端IP',
                parent_span_id  VARCHAR(64) COMMENT '父Span ID',
                
                request_method  VARCHAR(16) COMMENT '请求方法',
                request_uri     VARCHAR(512) COMMENT '请求URI',
                request_params  TEXT COMMENT '请求参数',
                request_body    TEXT COMMENT '请求体',
                request_headers TEXT COMMENT '请求头',
                client_ip       VARCHAR(64) COMMENT '客户端IP',
                user_agent      VARCHAR(512) COMMENT 'User-Agent',
                
                response_status INT COMMENT '响应状态码',
                response_body   TEXT COMMENT '响应体',
                duration        BIGINT COMMENT '耗时(毫秒)',
                
                user_id         BIGINT COMMENT '用户ID',
                username        VARCHAR(64) COMMENT '用户名',
                nickname        VARCHAR(64) COMMENT '用户昵称',
                
                module          VARCHAR(64) COMMENT '业务模块',
                operation       VARCHAR(64) COMMENT '操作类型',
                target_type     VARCHAR(64) COMMENT '对象类型',
                target_id       VARCHAR(64) COMMENT '对象ID',
                before_data     TEXT COMMENT '修改前数据',
                after_data      TEXT COMMENT '修改后数据',
                description     VARCHAR(512) COMMENT '操作描述',
                change_summary  VARCHAR(1024) COMMENT '变更摘要',
                
                class_name      VARCHAR(256) COMMENT '调用类名',
                method_name     VARCHAR(128) COMMENT '调用方法名',
                depth           INT COMMENT '调用栈深度',
                actual_duration BIGINT COMMENT '实际执行耗时(毫秒)',
                input_args      TEXT COMMENT '方法入参(JSON)',
                return_value    TEXT COMMENT '方法返回值(JSON)',
                
                success         TINYINT(1) DEFAULT 1 COMMENT '是否成功',
                error_message   VARCHAR(1024) COMMENT '错误消息',
                stack_trace     TEXT COMMENT '异常堆栈',
                
                create_time     BIGINT NOT NULL COMMENT '创建时间',
                
                INDEX idx_trace_id (trace_id),
                INDEX idx_span_id (span_id),
                INDEX idx_parent_span_id (parent_span_id),
                INDEX idx_user_id (user_id),
                INDEX idx_create_time (create_time),
                INDEX idx_log_type (log_type),
                INDEX idx_request_uri (request_uri(255))
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统日志表'
            """)
    void createTable(@Param("tableName") String tableName);

    /**
     * 删除日志表。
     *
     * @param tableName 表名
     */
    @Update("DROP TABLE IF EXISTS ${tableName}")
    void dropTable(@Param("tableName") String tableName);

    /**
     * 插入单条日志。
     *
     * @param tableName 表名
     * @param entity    日志实体
     */
    @Update("""
            INSERT INTO ${tableName} (
                id, trace_id, span_id, source, log_type, service_name, server_ip, parent_span_id,
                request_method, request_uri, request_params, request_body, request_headers,
                client_ip, user_agent, response_status, response_body, duration,
                user_id, username, nickname, module, operation,
                target_type, target_id, before_data, after_data, description, change_summary,
                class_name, method_name, depth, actual_duration, input_args, return_value,
                success, error_message, stack_trace, create_time
            ) VALUES (
                #{entity.id}, #{entity.traceId}, #{entity.spanId}, #{entity.source}, #{entity.logType}, #{entity.serviceName}, #{entity.serverIp}, #{entity.parentSpanId},
                #{entity.requestMethod}, #{entity.requestUri}, #{entity.requestParams}, #{entity.requestBody}, #{entity.requestHeaders},
                #{entity.clientIp}, #{entity.userAgent}, #{entity.responseStatus}, #{entity.responseBody}, #{entity.duration},
                #{entity.userId}, #{entity.username}, #{entity.nickname}, #{entity.module}, #{entity.operation},
                #{entity.targetType}, #{entity.targetId}, #{entity.beforeData}, #{entity.afterData}, #{entity.description}, #{entity.changeSummary},
                #{entity.className}, #{entity.methodName}, #{entity.depth}, #{entity.actualDuration}, #{entity.inputArgs}, #{entity.returnValue},
                #{entity.success}, #{entity.errorMessage}, #{entity.stackTrace}, #{entity.createTime}
            )
            """)
    void insertToTable(@Param("tableName") String tableName, @Param("entity") LogEntity entity);

    /**
     * 批量插入日志。
     *
     * @param tableName 表名
     * @param entities  日志实体列表
     */
    @Update("""
            <script>
            INSERT INTO ${tableName} (
                id, trace_id, span_id, source, log_type, service_name, server_ip, parent_span_id,
                request_method, request_uri, request_params, request_body, request_headers,
                client_ip, user_agent, response_status, response_body, duration,
                user_id, username, nickname, module, operation,
                target_type, target_id, before_data, after_data, description, change_summary,
                class_name, method_name, depth, actual_duration, input_args, return_value,
                success, error_message, stack_trace, create_time
            ) VALUES
            <foreach collection="entities" item="entity" separator=",">
            (
                #{entity.id}, #{entity.traceId}, #{entity.spanId}, #{entity.source}, #{entity.logType}, #{entity.serviceName}, #{entity.serverIp}, #{entity.parentSpanId},
                #{entity.requestMethod}, #{entity.requestUri}, #{entity.requestParams}, #{entity.requestBody}, #{entity.requestHeaders},
                #{entity.clientIp}, #{entity.userAgent}, #{entity.responseStatus}, #{entity.responseBody}, #{entity.duration},
                #{entity.userId}, #{entity.username}, #{entity.nickname}, #{entity.module}, #{entity.operation},
                #{entity.targetType}, #{entity.targetId}, #{entity.beforeData}, #{entity.afterData}, #{entity.description}, #{entity.changeSummary},
                #{entity.className}, #{entity.methodName}, #{entity.depth}, #{entity.actualDuration}, #{entity.inputArgs}, #{entity.returnValue},
                #{entity.success}, #{entity.errorMessage}, #{entity.stackTrace}, #{entity.createTime}
            )
            </foreach>
            </script>
            """)
    void insertBatchToTable(@Param("tableName") String tableName, @Param("entities") List<LogEntity> entities);
}
