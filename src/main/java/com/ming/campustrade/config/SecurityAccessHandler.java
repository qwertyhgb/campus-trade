package com.ming.campustrade.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ming.campustrade.common.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 【类是什么】
 * 安全访问处理器 —— 定制 Spring Security 的 401/403 响应格式。
 *
 * <p>【为什么需要它？】
 * Spring Security 默认处理未登录/无权限时，返回的是 302 重定向（跳转登录页）
 * 或 HTML 错误页。但我们是前后端分离的 REST API，前端需要统一格式的 JSON：
 * {"code":401,"msg":"请先登录"}。这个类就是负责把响应改写成这种格式。</p>
 *
 * <p>【一个类同时实现两个接口】
 * 两个接口的触发时机不同，但都要写 JSON，所以合并到一个类里：</p>
 * <ul>
 *   <li>{@link AuthenticationEntryPoint}（commence 方法）：
 *       未登录（匿名）访问受保护接口时触发 → 返回 401</li>
 *   <li>{@link AccessDeniedHandler}（handle 方法）：
 *       已登录但权限不足时触发 → 返回 403</li>
 * </ul>
 *
 * <p>【统一安全异常处理】
 * Spring Security 将未登录和无权限请求统一交给这个类处理，
 * 由此保证接口返回格式一致。</p>
 */
@Slf4j
@Component
public class SecurityAccessHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    /**
     * Jackson 的 JSON 序列化工具，由 Spring Boot 自动配置并注入。
     * 用于把 Result 对象转成 JSON 字符串写回响应体。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 【未登录访问受保护接口】→ 返回 401
     *
     * <p>触发场景：请求没带 Token（或 Token 无效），且访问的接口要求登录
     * （SecurityConfig 里 authenticated() 的规则），Security 就会调用这里。</p>
     *
     * @param authException 认证失败的异常对象，可以从中拿到失败原因
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 记录警告日志：方便运维发现"谁在尝试访问需要登录的接口"（可能是爬虫/攻击）
        log.warn("未登录访问受保护接口：method={}, uri={}",
                request.getMethod(), request.getRequestURI());
        writeJson(response, 401, "请先登录");
    }

    /**
     * 【已登录但权限不足】→ 返回 403
     *
     * <p>触发场景：用户已登录（SecurityContext 里有 Authentication），
     * 但授权规则（如 hasRole）判断他没有权限访问该接口。</p>
     *
     * <p>为什么用 SecurityContextHolder 而不是 request.getUserPrincipal()？
     * getUserPrincipal() 读的是 Servlet 容器的登录态（只对容器自身认证方式有效），
     * 我们用的是 Spring Security 的 Token 认证，它读不到 —— 所以要从
     * SecurityContextHolder（Security 自己的"当前用户"容器）里取。</p>
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        // 从 SecurityContext 拿当前认证信息，提取用户名用于日志
        // 注意：已登录用户走到这里 SecurityContext 一定不为空（能过认证必然有）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = (authentication != null && authentication.getPrincipal() != null)
                ? authentication.getPrincipal().toString() : "未知";
        log.warn("权限不足：method={}, uri={}, 用户={}",
                request.getMethod(), request.getRequestURI(), username);
        writeJson(response, 403, "没有权限访问");
    }

    /**
     * 【公共方法】把统一格式的错误响应写成 JSON。
     *
     * <p>两个接口共用这一段逻辑，抽出来避免重复代码。</p>
     *
     * @param response HTTP 响应对象
     * @param code     HTTP 状态码（401 或 403）
     * @param msg      给前端看的提示信息
     */
    private void writeJson(HttpServletResponse response, int code, String msg) throws IOException {
        // 设置 HTTP 状态码（前端可以根据状态码做统一拦截处理）
        response.setStatus(code);
        // 声明响应内容是 JSON + UTF-8 编码，防止中文乱码
        response.setContentType("application/json;charset=UTF-8");
        // 把 Result 对象序列化成 JSON 字符串写入响应体
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, msg)));
    }
}
