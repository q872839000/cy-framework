package com.cy.log.persistence.service;

import com.cy.log.config.LogProperties;
import com.cy.log.core.LogRecord;
import com.cy.log.core.LogType;
import com.cy.log.persistence.LogTableRouter;
import com.cy.log.persistence.entity.LogEntity;
import com.cy.log.persistence.mapper.LogMapper;
import com.cy.log.persistence.vo.TraceDetailVO;
import com.cy.log.persistence.vo.TraceDetailVO.SpanNode;
import com.cy.log.persistence.vo.TraceDetailVO.TraceSummary;
import com.cy.log.persistence.vo.TraceDetailVO.UserInfo;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.table.TableManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.cy.log.persistence.entity.table.LogEntityTableDef.LOG_ENTITY;

/**
 * 日志查询服务实现类
 * <p>
 * 提供跨分表的日志查询功能
 *
 * @author pcy
 * @date 2026/01/29
 */
@Slf4j
@RequiredArgsConstructor
public class LogQueryServiceImpl implements LogQueryService {

    /**
     * 日志Mapper
     */
    private final LogMapper logMapper;

    /**
     * 业务日志分表路由器
     */
    private final LogTableRouter businessTableRouter;

    /**
     * ACCESS 日志分表路由器
     */
    private final LogTableRouter accessTableRouter;

    /**
     * 日志配置
     */
    private final LogProperties properties;

    /**
     * LogEntity 注解的默认表名
     */
    private static final String BASE_TABLE = "sys_log";

    /**
     * 分页查询日志
     * <p>
     * 使用 SQL 级别 LIMIT/OFFSET 分页，避免将所有数据加载到内存导致 OOM。
     * 当跨多个分表时，逐表统计总数和逐表拉取数据并在内存中进行偏移计算。
     *
     * @param request 查询请求
     * @return 分页结果
     */
    @Override
    public PageResult<LogRecord> queryLogs(LogQueryRequest request) {
        Long startTime = request.startTime();
        Long endTime = request.endTime();
        if (startTime == null) {
            startTime = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L;
        }
        if (endTime == null) {
            endTime = System.currentTimeMillis();
        }

        // 根据日志类型选择路由器：null 表示查所有
        List<String> tables;
        if (request.logType() == null) {
            tables = new ArrayList<>(businessTableRouter.getQueryTables(startTime, endTime));
            tables.addAll(accessTableRouter.getQueryTables(startTime, endTime));
        } else if (request.logType() == LogType.ACCESS) {
            tables = accessTableRouter.getQueryTables(startTime, endTime);
        } else {
            tables = businessTableRouter.getQueryTables(startTime, endTime);
        }
        if (tables.isEmpty()) {
            return PageResult.empty(request.page(), request.size());
        }

        QueryWrapper countQuery = buildQueryWrapper(request, startTime, endTime);

        // 先统计各表的记录数，得到总数
        long totalCount = 0;
        List<TableCount> tableCounts = new ArrayList<>();
        for (String tableName : tables) {
            try {
                TableManager.setHintTableMapping(BASE_TABLE, tableName);
                long count = logMapper.selectCountByQuery(countQuery);
                if (count > 0) {
                    tableCounts.add(new TableCount(tableName, count));
                    totalCount += count;
                }
            } catch (Exception e) {
                log.warn("统计分表失败: {}, error: {}", tableName, e.getMessage());
            } finally {
                TableManager.clear();
            }
        }

        if (totalCount == 0) {
            return PageResult.empty(request.page(), request.size());
        }

        // 计算全局偏移量
        int pageSize = request.size();
        long globalOffset = (long) (request.page() - 1) * pageSize;

        if (globalOffset >= totalCount) {
            return PageResult.empty(request.page(), request.size());
        }

        // 按月份倒序排列（最新的月份先查）
        // 当 logType=null 混合了不同前缀的表时，按分区排序保证同月份表相邻
        tableCounts.sort((a, b) -> {
            String pa = extractPartitionFromTable(a.tableName);
            String pb = extractPartitionFromTable(b.tableName);
            return pb.compareTo(pa); // 降序
        });

        // 跳过前面表的记录，在目标表上执行 SQL LIMIT/OFFSET
        List<LogRecord> pageRecords = new ArrayList<>(pageSize);
        long skipped = 0;

        QueryWrapper dataQuery = buildQueryWrapper(request, startTime, endTime);

        for (TableCount tc : tableCounts) {
            if (pageRecords.size() >= pageSize) {
                break;
            }

            if (skipped + tc.count <= globalOffset) {
                skipped += tc.count;
                continue;
            }

            long offsetInTable = Math.max(0, globalOffset - skipped);
            int remaining = pageSize - pageRecords.size();

            try {
                TableManager.setHintTableMapping(BASE_TABLE, tc.tableName);
                // 使用 SQL LIMIT/OFFSET
                QueryWrapper paged = dataQuery.clone();
                paged.limit(offsetInTable, remaining);
                List<LogEntity> entities = logMapper.selectListByQuery(paged);
                for (LogEntity entity : entities) {
                    pageRecords.add(convertToRecord(entity));
                }
            } catch (Exception e) {
                log.warn("查询分表失败: {}, error: {}", tc.tableName, e.getMessage());
            } finally {
                TableManager.clear();
            }

            skipped += tc.count;
        }

        return PageResult.of(pageRecords, totalCount, request.page(), request.size());
    }

    private record TableCount(String tableName, long count) {
    }

    /**
     * 从表名中提取月份分区标识（YYYYMM）
     * <p>
     * 支持 sys_log_202604_001 和 access_log_202604_001 两种格式
     */
    private String extractPartitionFromTable(String tableName) {
        // 表名格式: {prefix}_{YYYYMM}_{NNN}
        // 倒数第二段是月份分区
        String[] parts = tableName.split("_");
        if (parts.length >= 2) {
            String candidate = parts[parts.length - 2];
            if (candidate.length() == 6) {
                return candidate;
            }
        }
        return tableName; // fallback
    }

    /**
     * 根据TraceId查询日志链路
     *
     * @param traceId 链路追踪ID
     * @return 日志列表
     */
    @Override
    public List<LogRecord> queryByTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return List.of();
        }

        // 查询最近6个月的所有分表（同时查业务表和 ACCESS 表）
        long startTime = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000;
        long endTime = System.currentTimeMillis();

        List<String> businessTables = businessTableRouter.getQueryTables(startTime, endTime);
        List<String> accessTables = accessTableRouter.getQueryTables(startTime, endTime);

        List<String> allTables = new ArrayList<>(businessTables.size() + accessTables.size());
        allTables.addAll(businessTables);
        allTables.addAll(accessTables);

        List<LogRecord> records = new ArrayList<>();

        QueryWrapper query = QueryWrapper.create()
                .where(LOG_ENTITY.TRACE_ID.eq(traceId))
                .orderBy(LOG_ENTITY.CREATE_TIME.asc());

        for (String tableName : allTables) {
            try {
                TableManager.setHintTableMapping(BASE_TABLE, tableName);
                List<LogEntity> entities = logMapper.selectListByQuery(query);
                for (LogEntity entity : entities) {
                    records.add(convertToRecord(entity));
                }
            } catch (Exception e) {
                log.warn("查询分表失败: {}, error: {}", tableName, e.getMessage());
            } finally {
                TableManager.clear();
            }
        }

        // 按 span 树深度优先排序（父节点在前，子节点按 createTime 升序）。
        // 若同一 trace 中无法建立稳定的 span 身份关系，则降级为按 createTime 排序，避免错误拼树。
        return toSpanTree(records);
    }

    /**
     * 构建查询条件
     *
     * @param request   查询请求
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 查询条件
     */
    private QueryWrapper buildQueryWrapper(LogQueryRequest request, Long startTime, Long endTime) {
        QueryWrapper query = QueryWrapper.create();

        query.where(LOG_ENTITY.CREATE_TIME.ge(startTime))
                .and(LOG_ENTITY.CREATE_TIME.le(endTime));

        if (request.logType() != null) {
            query.and(LOG_ENTITY.LOG_TYPE.eq(request.logType().name()));
        }
        if (request.userId() != null) {
            query.and(LOG_ENTITY.USER_ID.eq(request.userId()));
        }
        if (StringUtils.hasText(request.requestUri())) {
            query.and(LOG_ENTITY.REQUEST_URI.like(request.requestUri()));
        }
        if (StringUtils.hasText(request.module())) {
            query.and(LOG_ENTITY.MODULE.eq(request.module()));
        }
        if (request.success() != null) {
            query.and(LOG_ENTITY.SUCCESS.eq(request.success()));
        }
        if (StringUtils.hasText(request.keyword())) {
            query.and(LOG_ENTITY.DESCRIPTION.like(request.keyword())
                    .or(LOG_ENTITY.ERROR_MESSAGE.like(request.keyword())));
        }

        query.orderBy(LOG_ENTITY.CREATE_TIME.desc());
        return query;
    }

    /**
     * 根据TraceId查询结构化链路详情
     */
    @Override
    public TraceDetailVO queryTraceDetail(String traceId) {
        List<LogRecord> allRecords = queryByTraceId(traceId);
        if (allRecords.isEmpty()) {
            return null;
        }

        // 分离 ACCESS 日志和其他日志
        List<LogRecord> accessRecords = new ArrayList<>();
        for (LogRecord r : allRecords) {
            if (r.getLogType() == LogType.ACCESS) {
                accessRecords.add(r);
            }
        }

        if (accessRecords.isEmpty()) {
            return null;
        }

        // 找到最早的 startTime 作为链路起始时间
        long traceStart = accessRecords.stream()
                .mapToLong(r -> r.getCreateTime() != null ? r.getCreateTime() : Long.MAX_VALUE)
                .min().orElse(System.currentTimeMillis());

        List<SpanNode> allRoots;
        Set<String> serviceNames = new HashSet<>();

        accessRecords.sort(Comparator.comparing(
                LogRecord::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())));
        if (hasStableSpanIdentity(accessRecords)) {
            allRoots = buildIdentitySpanTree(accessRecords, traceStart);
        } else {
            allRoots = buildDepthBasedSpanTree(accessRecords, traceStart);
        }

        for (LogRecord r : accessRecords) {
            if (r.getServiceName() != null) {
                serviceNames.add(r.getServiceName());
            }
        }

        // 根节点按 startTime 排序
        allRoots.sort(Comparator.comparingLong(SpanNode::getStartTime));

        // 构建统计摘要
        TraceSummary summary = buildSummary(accessRecords, serviceNames.size());
        summary.setMaxDepth(Math.max(summary.getMaxDepth(), resolveTreeMaxDepth(allRoots)));

        // 构建顶层 VO
        TraceDetailVO vo = new TraceDetailVO();
        vo.setTraceId(traceId);
        vo.setStartTime(traceStart);
        vo.setSpanTree(allRoots);
        vo.setSummary(summary);

        // 从根 span 或 API 日志提取概览信息
        LogRecord rootRecord = resolveOverviewRootRecord(accessRecords, allRoots);
        vo.setServiceName(rootRecord.getServiceName());
        vo.setRequestUri(rootRecord.getRequestUri());
        vo.setRequestMethod(rootRecord.getRequestMethod());
        vo.setClientIp(rootRecord.getClientIp());

        // 总耗时：优先取树根 span 的 duration，缺失时回退到时间跨度。
        long totalDuration = rootRecord.getDuration() != null ? rootRecord.getDuration() : 0;
        if (totalDuration <= 0) {
            long maxEnd = accessRecords.stream()
                    .mapToLong(r -> (r.getCreateTime() != null ? r.getCreateTime() : 0)
                            + (r.getDuration() != null ? r.getDuration() : 0))
                    .max().orElse(traceStart);
            totalDuration = maxEnd - traceStart;
        }
        vo.setTotalDuration(totalDuration);
        vo.setSuccess(summary.getFailedSpans() == 0);

        // 用户信息
        if (rootRecord.getUserId() != null) {
            UserInfo user = new UserInfo();
            user.setUserId(rootRecord.getUserId());
            user.setUsername(rootRecord.getUsername());
            user.setNickname(rootRecord.getNickname());
            vo.setUser(user);
        }

        return vo;
    }

    /**
     * 解析顶层概览所对应的根记录。
     * <p>
     * 优先使用已构建树的第一个根节点对应的记录，避免跨服务时钟偏差导致
     * “最早 createTime” 并非真正入口节点；若无法匹配，再回退到排序后的第一条 ACCESS 记录。
     * </p>
     */
    private LogRecord resolveOverviewRootRecord(List<LogRecord> accessRecords, List<SpanNode> roots) {
        if (roots != null && !roots.isEmpty()) {
            Long rootId = roots.getFirst().getId();
            for (LogRecord record : accessRecords) {
                if (Objects.equals(record.getId(), rootId)) {
                    return record;
                }
            }
        }
        return accessRecords.getFirst();
    }

    /**
     * 使用 spanId / parentSpanId 身份模型构建 span 树。
     */
    private List<SpanNode> buildIdentitySpanTree(List<LogRecord> records, long traceStart) {
        Map<String, SpanNode> nodeBySpanId = new LinkedHashMap<>();
        Map<String, String> parentIdBySpanId = new HashMap<>();

        for (LogRecord record : records) {
            String spanId = record.getSpanId();
            if (!StringUtils.hasText(spanId) || nodeBySpanId.containsKey(spanId)) {
                continue;
            }
            SpanNode node = toSpanNode(record, traceStart);
            nodeBySpanId.put(spanId, node);
            parentIdBySpanId.put(spanId, record.getParentSpanId());
        }

        List<SpanNode> roots = new ArrayList<>();
        for (Map.Entry<String, SpanNode> entry : nodeBySpanId.entrySet()) {
            String spanId = entry.getKey();
            SpanNode node = entry.getValue();
            String parentSpanId = parentIdBySpanId.get(spanId);
            if (StringUtils.hasText(parentSpanId)
                    && !Objects.equals(parentSpanId, spanId)
                    && nodeBySpanId.containsKey(parentSpanId)) {
                nodeBySpanId.get(parentSpanId).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }

        normalizeDepths(roots, 1);
        sortSpanNodes(roots);
        return roots;
    }

    /**
     * 在 span 身份关系不稳定时，使用 depth + service/span 分组模型构建 span 树。
     */
    private List<SpanNode> buildDepthBasedSpanTree(List<LogRecord> accessRecords, long traceStart) {
        Map<String, List<LogRecord>> serviceGroups = new LinkedHashMap<>();
        for (LogRecord r : accessRecords) {
            String groupKey = (r.getServiceName() != null ? r.getServiceName() : "unknown")
                    + "::" + (r.getSpanId() != null ? r.getSpanId() : "default");
            serviceGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(r);
        }

        List<SpanNode> roots = new ArrayList<>();
        for (List<LogRecord> group : serviceGroups.values()) {
            group.sort(Comparator.comparing(
                    LogRecord::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())));
            roots.addAll(buildSpanTree(group, traceStart));
        }
        return roots;
    }

    /**
     * 从同一服务组的 ACCESS 日志（已按 createTime 排序）构建 span 树。
     * <p>
     * 算法：利用 depth 字段和 DFS 调用顺序，用栈还原父子关系。
     * <pre>
     * depth=1: A (root)
     * depth=2:   B (child of A)
     * depth=3:     C (child of B)
     * depth=2:   D (child of A, sibling of B)
     * </pre>
     */
    private List<SpanNode> buildSpanTree(List<LogRecord> records, long traceStart) {
        List<SpanNode> roots = new ArrayList<>();
        Deque<SpanNode> stack = new ArrayDeque<>();

        for (LogRecord r : records) {
            SpanNode node = toSpanNode(r, traceStart);
            int depth = r.getDepth() != null ? r.getDepth() : 1;

            // 弹出所有 depth >= 当前节点的栈元素（回溯到父级）
            while (!stack.isEmpty() && stack.peek().getDepth() >= depth) {
                stack.pop();
            }

            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().getChildren().add(node);
            }
            stack.push(node);
        }

        return roots;
    }

    /**
     * 递归按开始时间排序树节点，确保前端渲染顺序稳定。
     */
    private void sortSpanNodes(List<SpanNode> roots) {
        roots.sort(Comparator.comparingLong(SpanNode::getStartTime));
        for (SpanNode root : roots) {
            if (!root.getChildren().isEmpty()) {
                sortSpanNodes(root.getChildren());
            }
        }
    }

    /**
     * 依据树结构重写节点 depth，使跨服务子树也能反映真实全局层级。
     */
    private void normalizeDepths(List<SpanNode> nodes, int depth) {
        for (SpanNode node : nodes) {
            node.setDepth(depth);
            if (!node.getChildren().isEmpty()) {
                normalizeDepths(node.getChildren(), depth + 1);
            }
        }
    }

    /**
     * 将 LogRecord 转换为 SpanNode
     */
    private SpanNode toSpanNode(LogRecord r, long traceStart) {
        SpanNode node = new SpanNode();
        node.setId(r.getId());
        node.setServiceName(r.getServiceName());
        node.setClassName(r.getClassName());
        node.setMethodName(r.getMethodName());
        node.setDepth(r.getDepth() != null ? r.getDepth() : 1);
        node.setStartTime(r.getCreateTime() != null ? r.getCreateTime() : 0);
        node.setOffsetFromTraceStart(node.getStartTime() - traceStart);
        node.setDuration(r.getDuration() != null ? r.getDuration() : 0);
        node.setActualDuration(r.getActualDuration() != null ? r.getActualDuration() : node.getDuration());
        node.setInputArgs(r.getInputArgs());
        node.setReturnValue(r.getReturnValue());
        node.setSuccess(Boolean.TRUE.equals(r.getSuccess()));
        node.setErrorMessage(r.getErrorMessage());
        node.setModule(r.getModule());
        node.setDescription(r.getDescription());
        return node;
    }

    /**
     * 构建链路统计摘要
     */
    private TraceSummary buildSummary(List<LogRecord> accessRecords, int serviceCount) {
        TraceSummary summary = new TraceSummary();
        summary.setTotalSpans(accessRecords.size());
        summary.setTotalServices(serviceCount);

        int failedSpans = 0;
        int maxDepth = 0;
        String bottleneckMethod = null;
        long bottleneckDuration = 0;

        for (LogRecord r : accessRecords) {
            if (!Boolean.TRUE.equals(r.getSuccess())) {
                failedSpans++;
            }
            if (r.getDepth() != null && r.getDepth() > maxDepth) {
                maxDepth = r.getDepth();
            }
            long actual = r.getActualDuration() != null ? r.getActualDuration() : 0;
            if (actual > bottleneckDuration) {
                bottleneckDuration = actual;
                bottleneckMethod = (r.getClassName() != null ? r.getClassName() : "")
                        + "." + (r.getMethodName() != null ? r.getMethodName() : "");
            }
        }

        summary.setFailedSpans(failedSpans);
        summary.setMaxDepth(maxDepth);
        summary.setBottleneckMethod(bottleneckMethod);
        summary.setBottleneckDuration(bottleneckDuration);
        return summary;
    }

    /**
     * 判断 ACCESS 记录是否具备稳定的 span 身份模型。
     * <p>
     * 新模型下每个 ACCESS span 的 spanId 都必须唯一；一旦出现缺失或重复，
     * 查询时即回退到基于 depth 的保底建树逻辑。
     * </p>
     */
    private boolean hasStableSpanIdentity(List<LogRecord> records) {
        Set<String> spanIds = new HashSet<>(records.size());
        for (LogRecord record : records) {
            String spanId = record.getSpanId();
            if (!StringUtils.hasText(spanId) || !spanIds.add(spanId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 递归计算树结构的最大深度，用于修正跨服务场景下的统计摘要。
     */
    private int resolveTreeMaxDepth(List<SpanNode> roots) {
        int maxDepth = 0;
        for (SpanNode root : roots) {
            maxDepth = Math.max(maxDepth, root.getDepth());
            if (!root.getChildren().isEmpty()) {
                maxDepth = Math.max(maxDepth, resolveTreeMaxDepth(root.getChildren()));
            }
        }
        return maxDepth;
    }

    /**
     * 将日志记录按调用链路树深度优先排序。
     * <p>
     * 父节点在前，子节点紧随其后，同级按 createTime 升序。
     * 无法匹配到父节点的记录追加在末尾。
     *
     * @param records 原始记录列表
     * @return 树序排列的记录
     */
    private List<LogRecord> toSpanTree(List<LogRecord> records) {
        if (records == null || records.size() <= 1) {
            return records;
        }

        if (!hasStableSpanIdentityForOrdering(records)) {
            List<LogRecord> sorted = new ArrayList<>(records);
            sorted.sort(Comparator.comparing(
                    LogRecord::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())));
            return sorted;
        }

        // 构建 parentSpanId → children 映射
        Map<String, List<LogRecord>> childrenMap = new HashMap<>();
        List<LogRecord> roots = new ArrayList<>();

        for (LogRecord r : records) {
            String parentId = r.getParentSpanId();
            if (!StringUtils.hasText(parentId)) {
                roots.add(r);
            } else {
                childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(r);
            }
        }

        // 同级按 createTime 升序
        Comparator<LogRecord> byTime = Comparator.comparing(
                LogRecord::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()));
        roots.sort(byTime);
        childrenMap.values().forEach(list -> list.sort(byTime));

        // DFS 树序遍历
        List<LogRecord> result = new ArrayList<>(records.size());
        Set<String> visitedSpans = new HashSet<>();
        for (LogRecord root : roots) {
            dfs(root, childrenMap, result, visitedSpans);
        }

        // 无法匹配父节点的残余记录（父服务日志未持久化等场景）
        if (result.size() < records.size()) {
            Set<Long> visited = new HashSet<>();
            for (LogRecord r : result) {
                visited.add(r.getId());
            }
            for (LogRecord r : records) {
                if (!visited.contains(r.getId())) {
                    result.add(r);
                }
            }
        }

        return result;
    }

    /**
     * 判断全量日志列表是否可直接按 spanId / parentSpanId 排成树序。
     * <p>
     * 若 trace 中混入共享同一 spanId 的不同日志类型，则无法安全建树，
     * 应退回时间序输出。
     * </p>
     */
    private boolean hasStableSpanIdentityForOrdering(List<LogRecord> records) {
        Set<String> spanIds = new HashSet<>(records.size());
        for (LogRecord record : records) {
            if (!StringUtils.hasText(record.getSpanId())) {
                continue;
            }
            if (!spanIds.add(record.getSpanId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 深度优先遍历 span 树
     */
    private void dfs(LogRecord node, Map<String, List<LogRecord>> childrenMap, List<LogRecord> result, Set<String> visitedSpans) {
        result.add(node);
        String spanId = node.getSpanId();
        if (StringUtils.hasText(spanId) && visitedSpans.add(spanId)) {
            List<LogRecord> children = childrenMap.get(spanId);
            if (children != null) {
                for (LogRecord child : children) {
                    dfs(child, childrenMap, result, visitedSpans);
                }
            }
        }
    }

    /**
     * 将LogEntity转换为LogRecord
     *
     * @param entity 数据库实体
     * @return 日志记录
     */
    private LogRecord convertToRecord(LogEntity entity) {
        LogRecord record = new LogRecord();
        BeanUtils.copyProperties(entity, record);

        // 字符串转枚举
        if (StringUtils.hasText(entity.getLogType())) {
            try {
                record.setLogType(LogType.valueOf(entity.getLogType()));
            } catch (Exception e) {
                record.setLogType(LogType.API);
            }
        }

        return record;
    }
}
