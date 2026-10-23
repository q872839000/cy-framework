package com.cy.log.persistence.service;

import com.cy.log.core.LogRecord;
import com.cy.log.core.LogType;
import com.cy.log.persistence.vo.TraceDetailVO;

import java.util.List;

/**
 * 日志查询服务接口
 * <p>
 * 提供日志的查询功能，支持跨分表查询
 *
 * @author pcy
 * @date 2026/01/29
 */
public interface LogQueryService {

    /**
     * 分页查询日志
     *
     * @param request 查询请求
     * @return 分页结果
     */
    PageResult<LogRecord> queryLogs(LogQueryRequest request);

    /**
     * 根据TraceId查询日志链路
     *
     * @param traceId 链路追踪ID
     * @return 日志列表
     */
    List<LogRecord> queryByTraceId(String traceId);

    /**
     * 根据TraceId查询结构化链路详情
     * <p>
     * 返回树状结构的调用链路数据，包含：
     * <ul>
     *     <li>span 树（方法调用的父子关系）</li>
     *     <li>瀑布流时间偏移（offsetFromTraceStart）</li>
     *     <li>统计摘要（总 span 数、失败数、瓶颈方法等）</li>
     * </ul>
     * 专为前端图形化渲染设计。
     *
     * @param traceId 链路追踪ID
     * @return 结构化链路详情，未找到时返回 null
     */
    TraceDetailVO queryTraceDetail(String traceId);

    /**
     * 日志查询请求
     */
    record LogQueryRequest(
            /** 开始时间（毫秒时间戳） */
            Long startTime,
            /** 结束时间（毫秒时间戳） */
            Long endTime,
            /** 日志类型 */
            LogType logType,
            /** 用户ID */
            Long userId,
            /** 请求URI（模糊匹配） */
            String requestUri,
            /** 业务模块 */
            String module,
            /** 是否成功 */
            Boolean success,
            /** 关键词（搜索描述、错误消息等） */
            String keyword,
            /** 页码（从1开始） */
            int page,
            /** 每页条数 */
            int size
    ) {
        /**
         * 创建默认查询请求
         *
         * @param page 页码
         * @param size 每页条数
         * @return 查询请求
         */
        public static LogQueryRequest of(int page, int size) {
            return new LogQueryRequest(null, null, null, null, null, null, null, null, page, size);
        }
    }

    /**
     * 分页结果
     *
     * @param <T> 数据类型
     */
    record PageResult<T>(
            /** 数据列表 */
            List<T> records,
            /** 总记录数 */
            long total,
            /** 当前页码 */
            int page,
            /** 每页条数 */
            int size,
            /** 总页数 */
            int pages
    ) {
        /**
         * 创建空结果
         *
         * @param page 页码
         * @param size 每页条数
         * @param <T>  数据类型
         * @return 空结果
         */
        public static <T> PageResult<T> empty(int page, int size) {
            return new PageResult<>(List.of(), 0, page, size, 0);
        }

        /**
         * 创建分页结果
         *
         * @param records 数据列表
         * @param total   总记录数
         * @param page    页码
         * @param size    每页条数
         * @param <T>     数据类型
         * @return 分页结果
         */
        public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
            int pages = (int) Math.ceil((double) total / size);
            return new PageResult<>(records, total, page, size, pages);
        }
    }
}
