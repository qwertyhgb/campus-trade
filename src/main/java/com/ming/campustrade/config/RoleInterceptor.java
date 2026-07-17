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
 * 角色权限拦截器
 * 在 LoginInterceptor 之后执行，检查当前用户角色是否满足接口要求
 */
@Slf4j
@Component
public class RoleInterceptor implements HandlerInterceptor {

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        // 如果不是 Controller 方法（比如静态资源），直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查方法上是否有 @RequireRole 注解
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);

        // 如果同时看类上的注解（方法上的优先级更高）
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }

        // 没有标注 @RequireRole，说明只需要登录即可，放行
        if (requireRole == null) {
            return true;
        }

        // 从 ThreadLocal 获取当前登录用户
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null) {
            return forbidden(response, "请先登录");
        }

        // 检查角色是否满足要求
        int requiredRole = requireRole.value();
        if (currentUser.getRole() == null || currentUser.getRole() < requiredRole) {
            log.warn("角色权限不足：userId={}, requiredRole={}, currentRole={}",
                    currentUser.getId(), requiredRole, currentUser.getRole());
            return forbidden(response, "无权限访问");
        }

        log.info("角色校验通过：userId={}, role={}", currentUser.getId(), currentUser.getRole());
        return true;
    }

    /**
     * 返回 403 无权限响应
     */
    private boolean forbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        String json = objectMapper.writeValueAsString(Result.error(403, message));
        response.getWriter().write(json);
        return false;
    }
}