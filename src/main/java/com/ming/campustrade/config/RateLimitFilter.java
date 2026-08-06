package com.ming.campustrade.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.RateLimitScene;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.service.RateLimitService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 限流过滤器 —— 在请求进入业务之前按“IP + 场景”做固定窗口限流。
 *
 * <p><b>为什么用过滤器而不是在 Controller 里限流？</b><br>
 * ① 过滤器在请求入口统一执行，所有接口的限流逻辑只写一份；<br>
 * ② 被限流的请求<b>根本不会进入 Controller</b>，不占用业务线程和数据库资源
 * （限流的目的就是保护系统负载，拦截越早越好）。</p>
 *
 * <p><b>为什么继承 OncePerRequestFilter？</b><br>
 * 普通 Filter 在一个请求被转发（forward）时可能执行多次；本过滤器每次请求
 * 只能计数一次，否则转发场景下同一个请求会被重复计数、提前触发限流。
 * OncePerRequestFilter 保证每个请求只执行一次 doFilterInternal。</p>
 *
 * <p><b>客户端标识为什么用 request.getRemoteAddr() 而不是 X-Forwarded-For？</b><br>
 * X-Forwarded-For 是 HTTP 头，客户端可以随意伪造（比如伪造 127.0.0.1 绕过限流）。
 * getRemoteAddr() 是 TCP 层对端地址，无法伪造。项目部署在受信任 Nginx 后面时，
 * 再统一配置“由 Nginx 改写 X-Forwarded-For 并信任它”的真实 IP 策略。</p>
 *
 * <p><b>限流拒绝时为什么自己写 JSON 响应，而不是抛 BusinessException？</b><br>
 * 过滤器阶段在 Controller 之外，全局异常处理器（GlobalExceptionHandler）
 * 管不到这里 —— 抛异常只会被 Servlet 容器当成 500 处理。
 * 所以限流拒绝时必须自己写响应：HTTP 429 + 统一格式 JSON + Retry-After 头。</p>
 *
 * @author ming
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** 限流服务：每个请求只调用一次 isAllowed（有副作用，重复调用会重复计数）。 */
    private final RateLimitService rateLimitService;

    /** Jackson ObjectMapper：把统一错误响应序列化为 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入限流服务和 JSON 序列化工具。
     *
     * @param rateLimitService 限流服务
     * @param objectMapper     Jackson 2 ObjectMapper（Spring Boot 自动配置的实例）
     */
    public RateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    /**
     * 详情页路径的匹配规则：/activity/{数字ID}。
     *
     * <p>只匹配纯数字 ID（如 /activity/123），不会误匹配 /activity/my、
     * /activity/hot、/activity/list 等带字母的路径。</p>
     */
    private static final String ACTIVITY_DETAIL_PATH_PATTERN = "^/activity/\\d+$";

    /**
     * 限流拦截入口：每个请求只执行一次（OncePerRequestFilter 保证）。
     *
     * <p>流程：解析路径和请求方法 → 确定限流场景 → 不在限流表内的直接放行
     * → 调用限流服务计数 → 超限返回 429，未超限继续过滤器链。</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 去掉 contextPath（部署前缀），得到应用内的真实路径
        String path = request.getRequestURI().substring(request.getContextPath().length());

        // 2. 按“HTTP 方法 + 精确路径”确定限流场景
        RateLimitScene scene = resolveScene(request.getMethod(), path);
        if (scene == null) {
            // 不在限流表中的请求（编辑、删除、下架等）：直接放行，不计数
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 客户端标识暂时使用 TCP 对端地址（不信任可伪造的 X-Forwarded-For）
        String clientKey = request.getRemoteAddr();

        // 4. 原子计数并判断是否超限（每个请求只调用一次）
        boolean allowed = rateLimitService.isAllowed(scene, clientKey);
        if (!allowed) {
            // 5. 超限：自己写统一 JSON 响应（过滤器阶段没有全局异常处理兜底）
            log.warn("限流拦截：scene={}, clientKey={}, path={}", scene.getValue(), clientKey, path);
            reject(response);
            return;
        }

        // 6. 未超限：继续走过滤器链（认证 → 授权 → Controller）
        filterChain.doFilter(request, response);
    }

    /**
     * 根据 HTTP 方法和路径确定限流场景。
     *
     * @param method HTTP 方法（GET/POST 等）
     * @param path   去掉 contextPath 后的应用内路径
     * @return 命中的限流场景；不在限流表内返回 null（不计数、直接放行）
     */
    private RateLimitScene resolveScene(String method, String path) {
        // 登录接口：POST /user/login → 每 IP 每分钟最多 10 次（防暴力破解）
        if ("POST".equals(method) && "/user/login".equals(path)) {
            return RateLimitScene.LOGIN;
        }
        // 活动公开查询（GET）：列表、热门榜、数字 ID 详情 → 每 IP 每分钟最多 60 次
        if ("GET".equals(method)
                && ("/activity/list".equals(path)
                    || "/activity/hot".equals(path)
                    || path.matches(ACTIVITY_DETAIL_PATH_PATTERN))) {
            return RateLimitScene.ACTIVITY_QUERY;
        }
        return null;
    }

    /**
     * 写出限流拒绝响应：HTTP 429 + 统一 JSON + Retry-After 提示。
     *
     * <p>不调用 chain.doFilter：请求到此为止，不再进入认证和业务。
     * Retry-After 告诉前端（或标准客户端）大约 60 秒后可以重试。</p>
     *
     * @param response HTTP 响应对象
     * @throws IOException JSON 写出失败时抛出（由 Servlet 容器兜底处理）
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // 429
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", String.valueOf(RedisConstants.RATE_LIMIT_WINDOW_SECONDS));
        // 与 Controller 返回的格式完全一致：{"code":9401,"msg":"操作过于频繁，请稍后再试",...}
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(ResultCode.RATE_LIMIT_EXCEEDED)));
    }
}
