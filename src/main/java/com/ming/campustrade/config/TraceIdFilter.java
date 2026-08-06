package com.ming.campustrade.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 请求追踪 ID 过滤器 —— 为每个请求生成唯一 traceId，贯穿整个请求链路。
 *
 * <p><b>解决什么问题？</b><br>
 * 一个请求会经过：过滤器 → 拦截器 → Controller → Service → Mapper（多次 SQL）。
 * 没有 traceId 时，日志文件里这些环节的记录散落各处，多用户并发时无法区分
 * “哪条日志属于哪个请求”。有了 traceId：</p>
 * <ul>
 *   <li>写入 MDC（日志上下文）：logback 的 pattern 中输出 [traceId=xxx]，
 *       同一请求的所有日志都带同一个 ID；</li>
 *   <li>写入响应头 X-Trace-Id：前端/排查工具拿到后，可以直接在日志文件里
 *       搜索这个 ID 定位整条调用链；</li>
 *   <li>写入 operation_log 表：审计日志与文件日志双向关联。</li>
 * </ul>
 *
 * <p><b>为什么继承 OncePerRequestFilter？</b><br>
 * 与限流过滤器同理：转发场景下普通 Filter 会执行多次，导致 traceId 被
 * 重新生成或 MDC 清理时机错乱；OncePerRequestFilter 保证每请求只执行一次。</p>
 *
 * <p><b>为什么必须 finally 清理 MDC？</b><br>
 * MDC 是 ThreadLocal 实现：Tomcat 线程池复用线程，如果不清理，
 * 下一个请求会带着上一个请求的 traceId 打日志，链路彻底串号（严重 bug）。</p>
 *
 * @author ming
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 中的 traceId 键名（logback pattern 里用 %X{traceId} 引用）。 */
    private static final String TRACE_ID_KEY = "traceId";

    /** 响应头名称：把 traceId 返回给前端。 */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 生成唯一追踪 ID（UUID 去掉横线，缩短长度便于日志阅读）
        String traceId = UUID.randomUUID().toString().replace("-", "");

        // 2. 写入 MDC：本请求后续所有日志（Controller/Service/Mapper）都会带上 traceId
        MDC.put(TRACE_ID_KEY, traceId);

        // 3. 写入响应头：前端或排查工具可以用它反查日志
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            // 4. 继续过滤器链（限流 → 认证 → 授权 → Controller）
            filterChain.doFilter(request, response);
        } finally {
            // 5. 必须清理：线程复用场景下防止下一个请求串号（见类注释）
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
