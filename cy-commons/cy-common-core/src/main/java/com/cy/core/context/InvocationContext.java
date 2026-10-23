package com.cy.core.context;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.UUID;

/**
 * 统一调用上下文模型。
 * <p>
 * 贯穿 Servlet / WebFlux / ORM / 日志 / AOP / 异步执行 的请求级上下文，
 * 替代原有的 {@code UserContext} 和 {@code ReactiveUserContext}。
 * </p>
 * <h3>生命周期</h3>
 * <ol>
 *   <li>入口层（{@code InvocationContextFilter} / {@code InvocationContextWebFilter}）创建匿名上下文</li>
 *   <li>认证层（{@code AuthInterceptor} / {@code ReactiveAuthWebFilter}）升级为已认证上下文</li>
 *   <li>业务层通过 {@link InvocationContextAccessor} 隐式读取，无需显式传参</li>
 *   <li>异步/边界层通过 {@link InvocationContextAccessor#wrap} 显式传播</li>
 * </ol>
 * <h3>设计约束</h3>
 * <ul>
 *   <li>不可变对象（通过 {@code toBuilder()} 派生新实例）</li>
 *   <li>业务 Service 接口不应将本类作为方法参数</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/06
 * @see InvocationScope
 * @see InvocationContextAccessor
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class InvocationContext {

    // ==================== 来源常量 ====================

    /** 来源：外部直接访问（浏览器 / Postman 等） */
    public static final String SOURCE_EXTERNAL = "external";
    /** 来源：经由网关转发 */
    public static final String SOURCE_GATEWAY = "gateway";
    /** 来源：服务间内部调用 */
    public static final String SOURCE_INTERNAL = "internal";
    /** 来源：系统自发调用（定时任务 / 消息消费等） */
    public static final String SOURCE_SYSTEM = "system";
    // ==================== HTTP 头常量 ====================

    /** HTTP 请求头：全链路追踪 ID */
    public static final String TRACE_HEADER = "X-Trace-Id";
    /** HTTP 请求头：当前调用作用域的 Span ID */
    public static final String SPAN_HEADER = "X-Span-Id";
    /** Servlet / WebFlux 请求属性键，用于在请求范围内传递上下文 */
    public static final String REQUEST_ATTRIBUTE = InvocationContext.class.getName();

    // ==================== 上下文字段 ====================

    /** 已认证用户信息，未认证时为 {@code null} */
    private final AuthUser authUser;
    /** 全链路追踪 ID，贯穿整个请求链路 */
    private final String traceId;
    /**
     * 当前调用作用域的 Span ID。
     * <p>
     * 入口过滤器创建的基础上下文中，该值代表“当前服务入口请求”的 spanId；
     * 在 {@code @AccessLog} 等需要方法级追踪的场景下，可通过 {@code toBuilder()} 派生为
     * “当前活动方法 span”的 spanId，以驱动跨服务传播与日志关联。
     * </p>
     */
    private final String spanId;
    /**
     * 当前调用作用域的父 Span ID，指向调用方的 spanId。
     * <p>
     * 入口上下文中通常来自上游请求头；在方法级追踪场景下，则随当前活动 span 一起派生，
     * 用于构建跨服务 / 同服务统一的调用链路树。
     * </p>
     */
    private final String parentSpanId;
    /** 请求来源标识，取值参见 {@code SOURCE_*} 常量 */
    private final String source;
    /** 客户端 IP 地址 */
    private final String clientIp;
    /** 请求方法（GET/POST 等），非 HTTP 场景可为 {@code null} */
    private final String requestMethod;
    /** 请求 URI，非 HTTP 场景可为 {@code null} */
    private final String requestUri;
    /** 是否已通过认证 */
    private final boolean authenticated;
    /** 是否由网关转发 */
    private final boolean gatewayForwarded;

    // ==================== 工厂方法 ====================

    /**
     * 创建匿名（未认证）上下文。
     * <p>
     * 通常由入口 Filter 在认证之前调用。
     * </p>
     *
     * @param traceId   全链路追踪 ID
     * @param spanId    当前作用域的 Span ID
     * @param source    请求来源
     * @param clientIp  客户端 IP
     * @return 匿名上下文实例
     */
    public static InvocationContext anonymous(String traceId, String spanId, String source, String clientIp) {
        return anonymous(traceId, spanId, null, source, clientIp, null, null);
    }

    /**
     * 创建匿名（未认证）上下文，并同时携带 HTTP 请求方法与 URI。
     * <p>
     * 适用于入口过滤器已能确定请求路径信息的场景，如 Servlet Filter / WebFlux WebFilter。
     * </p>
     *
     * @param traceId       全链路追踪 ID
     * @param spanId        当前作用域的 Span ID
     * @param source        请求来源
     * @param clientIp      客户端 IP
     * @param requestMethod 请求方法（GET/POST 等）
     * @param requestUri    请求 URI
     * @return 匿名上下文实例
     */
    public static InvocationContext anonymous(String traceId, String spanId, String source, String clientIp,
                                              String requestMethod, String requestUri) {
        return anonymous(traceId, spanId, null, source, clientIp, requestMethod, requestUri);
    }

    /**
     * 创建匿名（未认证）上下文（含父 Span）。
     *
     * @param traceId      全链路追踪 ID
     * @param spanId       当前作用域的 Span ID
     * @param parentSpanId 调用方的 spanId（父 Span ID），根节点传 {@code null}
     * @param source       请求来源
     * @param clientIp     客户端 IP
     * @return 匿名上下文实例
     */
    public static InvocationContext anonymous(String traceId, String spanId, String parentSpanId,
                                              String source, String clientIp) {
        return anonymous(traceId, spanId, parentSpanId, source, clientIp, null, null);
    }

    /**
     * 创建匿名（未认证）上下文（含父 Span 与 HTTP 请求元信息）。
     *
     * @param traceId       全链路追踪 ID
     * @param spanId        当前作用域的 Span ID
     * @param parentSpanId  调用方的 spanId（父 Span ID）
     * @param source        请求来源
     * @param clientIp      客户端 IP
     * @param requestMethod 请求方法（GET/POST 等）
     * @param requestUri    请求 URI
     * @return 匿名上下文实例
     */
    public static InvocationContext anonymous(String traceId, String spanId, String parentSpanId,
                                              String source, String clientIp,
                                              String requestMethod, String requestUri) {
        return InvocationContext.builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .source(source)
                .clientIp(clientIp)
                .requestMethod(requestMethod)
                .requestUri(requestUri)
                .authenticated(false)
                .gatewayForwarded(false)
                .build();
    }

    public InvocationContext withAuthenticatedUser(AuthUser user, boolean gatewayForwarded) {
        return this.toBuilder()
                .authUser(user)
                .source(gatewayForwarded ? SOURCE_GATEWAY : source)
                .authenticated(user != null)
                .gatewayForwarded(gatewayForwarded)
                .build();
    }

    /**
     * 创建系统级上下文（无真实用户）。
     * <p>
     * 用于定时任务、消息消费、Butler 等无外部请求发起的场景。
     * </p>
     *
     * @return 系统上下文实例
     */
    public static InvocationContext system() {
        String traceId = generateId();
        String spanId = generateId();
        return system(traceId, spanId, SOURCE_SYSTEM, null);
    }

    /**
     * 创建指定追踪信息的系统级上下文。
     *
     * @param traceId   全链路追踪 ID
     * @param spanId    当前作用域的 Span ID
     * @param source    请求来源
     * @param clientIp  客户端 IP
     * @return 系统上下文实例
     */
    public static InvocationContext system(String traceId, String spanId, String source, String clientIp) {
        return system(traceId, spanId, null, source, clientIp);
    }

    /**
     * 创建指定追踪信息的系统级上下文（含父 Span）。
     *
     * @param traceId      全链路追踪 ID
     * @param spanId       当前作用域的 Span ID
     * @param parentSpanId 调用方的 spanId（父 Span ID）
     * @param source       请求来源
     * @param clientIp     客户端 IP
     * @return 系统上下文实例
     */
    public static InvocationContext system(String traceId, String spanId, String parentSpanId,
                                           String source, String clientIp) {
        AuthUser authUser = new AuthUser();
        authUser.setUserId(0L);
        authUser.setUsername("system");
        authUser.setNickname("system");
        authUser.setSuperAdmin(false);
        return InvocationContext.builder()
                .authUser(authUser)
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .source(source)
                .clientIp(clientIp)
                .authenticated(true)
                .gatewayForwarded(false)
                .build();
    }

    /**
     * 生成 32 位无连字符的 UUID，用作 traceId / spanId。
     *
     * @return UUID 字符串
     */
    public static String resolveId(String value) {
        return value != null && !value.isBlank() ? value : generateId();
    }

    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ==================== 便捷委托方法 ====================

    /**
     * 获取当前用户 ID，未认证时返回 {@code null}。
     *
     * @return 用户 ID 或 {@code null}
     */
    public Long getUserId() {
        return authUser != null ? authUser.getUserId() : null;
    }

    /**
     * 获取当前用户登录名，未认证时返回 {@code null}。
     *
     * @return 用户名或 {@code null}
     */
    public String getUsername() {
        return authUser != null ? authUser.getUsername() : null;
    }

    /**
     * 获取当前用户昵称，未认证时返回 {@code null}。
     *
     * @return 昵称或 {@code null}
     */
    public String getNickname() {
        return authUser != null ? authUser.getNickname() : null;
    }

    /**
     * 获取当前用户角色列表，未认证时返回 {@code null}。
     *
     * @return 角色列表或 {@code null}
     */
    public List<String> getRoles() {
        return authUser != null ? authUser.getRoles() : null;
    }

    /**
     * 获取当前用户权限列表，未认证时返回 {@code null}。
     *
     * @return 权限列表或 {@code null}
     */
    public List<String> getPermissions() {
        return authUser != null ? authUser.getPermissions() : null;
    }

    /**
     * 判断当前用户是否为超级管理员。
     *
     * @return 是否超级管理员
     */
    public boolean isSuperAdmin() {
        return authUser != null && Boolean.TRUE.equals(authUser.getSuperAdmin());
    }

    /**
     * 判断当前用户是否拥有指定角色。
     *
     * @param role 角色标识
     * @return 是否拥有
     */
    public boolean hasRole(String role) {
        List<String> roles = getRoles();
        return roles != null && roles.contains(role);
    }

    /**
     * 判断当前用户是否拥有指定权限。
     *
     * @param permission 权限标识
     * @return 是否拥有
     */
    public boolean hasPermission(String permission) {
        List<String> permissions = getPermissions();
        return permissions != null && permissions.contains(permission);
    }
}
