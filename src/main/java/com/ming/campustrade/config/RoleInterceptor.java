package com.ming.campustrade.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.RequireRole;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 角色权限拦截器 —— 在 LoginInterceptor 之后执行，检查当前用户角色是否满足接口要求。
 *
 * <h2>执行时机</h2>
 * <p>
 * 在 WebMvcConfig 中，拦截器的注册顺序决定了执行顺序：
 * <ol>
 *   <li>{@code LoginInterceptor}：先验证 token 是否有效，把用户信息存入 ThreadLocal</li>
 *   <li>{@code RoleInterceptor}（本类）：再从 ThreadLocal 取出用户，检查角色是否足够</li>
 * </ol>
 * 这样设计的好处是「职责分离」：登录校验和角色校验各管各的，互不耦合。
 * </p>
 *
 * <h2>角色数值约定</h2>
 * <ul>
 *   <li>role = 0：普通用户</li>
 *   <li>role = 1：管理员</li>
 * </ul>
 * <p>
 * 使用「大于等于」比较：{@code @RequireRole(1)} 表示「至少是管理员」，
 * 如果将来加入 role=2（超级管理员），也自然满足 >= 1 的条件，无需改代码。
 * </p>
 *
 * @author Ming
 * @since 1.0.0
 * @see com.ming.campustrade.common.annotation.RequireRole
 * @see UserHolder
 */
@Slf4j
@Component
public class RoleInterceptor implements HandlerInterceptor {

    /**
     * Jackson 的 JSON 序列化工具，由 Spring Boot 自动配置并注入。
     * 用于把 Result 对象转成 JSON 字符串写回响应体。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 在 Controller 方法执行「之前」拦截，进行角色权限校验。
     *
     * <p>返回 true 表示放行（请求继续往下走），返回 false 表示拦截（请求到此为止）。</p>
     *
     * @param request  HTTP 请求对象
     * @param response HTTP 响应对象（权限不足时直接往里面写 403 JSON）
     * @param handler  即将执行的处理器（通常是 HandlerMethod，即 Controller 的某个方法）
     * @return true=放行，false=已拦截
     * @throws Exception 写响应时可能抛出 IO 异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        // ========== 第 1 步：判断请求目标是不是一个 Controller 方法 ==========
        // 如果请求目标是静态资源（如 /doc.html、/webjars/...），handler 不是 HandlerMethod，
        // 此时无需做权限校验，直接放行。
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // ========== 第 2 步：查找 @RequireRole 注解（方法级优先于类级） ==========
        // 优先从「方法」上找注解：
        // 比如一个 Controller 类上标了 @RequireRole(1)（整个类都要管理员），
        // 但某个方法想对普通用户开放，就可以在该方法上标 @RequireRole(0) 来「覆盖」类级配置。
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);

        // 如果方法上没有，再退而求其次从「类」上找：
        // 这样可以在类上统一标注 @RequireRole(1)，省去每个方法都写一遍的麻烦。
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }

        // 如果方法和类上都没有 @RequireRole 注解，说明这个接口不做角色限制，直接放行。
        // （注意：没有 @RequireRole 不代表不需要登录，登录校验由 LoginInterceptor 负责）
        if (requireRole == null) {
            return true;
        }

        // ========== 第 3 步：获取当前登录用户 ==========
        // UserHolder 内部是 ThreadLocal，LoginInterceptor 已经把用户信息存进去了。
        // 如果为 null，说明 LoginInterceptor 没有正确设置（理论上不会走到这里，因为未登录会被前置拦截器拦住），
        // 但作为防御性编程，还是做一个兜底判断。
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null) {
            return forbidden(response, "请先登录");
        }

        // ========== 第 4 步：比较角色等级 ==========
        // requireRole.value() 是接口要求的最低角色等级（如 1 = 管理员）
        // currentUser.getRole() 是当前用户的实际角色等级
        // 使用 >= 比较：只要用户角色「不低于」要求等级就放行
        int requiredRole = requireRole.value();
        if (currentUser.getRole() == null || currentUser.getRole() < requiredRole) {
            // 角色不够，记录警告日志（用 warn 级别，方便运维监控异常访问）
            log.warn("角色权限不足：userId={}, requiredRole={}, currentRole={}",
                    currentUser.getId(), requiredRole, currentUser.getRole());
            return forbidden(response, "无权限访问");
        }

        // 校验通过，用 debug 级别记录（生产环境通常不开 DEBUG，避免日志量过大）
        log.debug("角色校验通过：userId={}, role={}", currentUser.getId(), currentUser.getRole());
        return true;
    }

    /**
     * 向客户端返回 403 Forbidden 响应。
     *
     * <p>
     * 为什么不直接抛异常让全局异常处理器处理？
     * 因为拦截器执行在 Controller 之前，此时 @RestControllerAdvice 的全局异常处理器
     * 还无法捕获拦截器中抛出的异常（它只能捕获 Controller 层及之后的异常）。
     * 所以这里手动构造 JSON 响应写回去。
     * </p>
     *
     * @param response HTTP 响应对象
     * @param message  错误提示信息（如"无权限访问"）
     * @return 始终返回 false，表示请求已被拦截，不再继续执行
     * @throws Exception 写响应体时可能抛出 IO 异常
     */
    private boolean forbidden(HttpServletResponse response, String message) throws Exception {
        // 设置 HTTP 状态码为 403（Forbidden：服务器理解请求，但拒绝授权）
        response.setStatus(403);
        // 设置响应内容类型为 JSON，并指定 UTF-8 编码防止中文乱码
        response.setContentType("application/json;charset=UTF-8");
        // 把统一的 Result 错误对象序列化为 JSON 字符串
        String json = objectMapper.writeValueAsString(Result.error(403, message));
        // 写入响应体，前端收到后可以根据 code=403 做相应提示
        response.getWriter().write(json);
        // 返回 false 告诉 Spring MVC：请求已处理完毕，不要再往下走了
        return false;
    }
}
