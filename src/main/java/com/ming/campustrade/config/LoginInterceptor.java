package com.ming.campustrade.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.PublicApi;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;

/**
 * 登录拦截器
 * 用于拦截请求，验证用户是否已登录
 * 通过检查请求头中的 Token 来判断用户登录状态
 */
@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 请求处理前的拦截方法
     * 在请求到达 Controller 之前执行
     * 用于验证用户登录状态
     *
     * @param request  HTTP 请求对象
     * @param response HTTP 响应对象
     * @param handler  处理器对象（通常是 Controller 方法）
     * @return true: 放行请求, false: 拦截请求
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        // 如果不是 Controller 方法（比如静态资源请求），直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查方法上是否标注了 @PublicApi，标注了就直接放行（不需要登录）
        if (handlerMethod.getMethodAnnotation(PublicApi.class) != null) {
            return true;
        }

        // 检查类上是否标注了 @PublicApi
        if (handlerMethod.getBeanType().getAnnotation(PublicApi.class) != null) {
            return true;
        }

        // ===== 以下是需要登录才能访问的接口，进行 Token 验证 =====

        // 1. 从请求头获取 Token
        String token = request.getHeader("Authorization");

        // 2. 验证 Token 是否存在
        if (!StringUtils.hasText(token)) {
            return unauthorized(response);
        }

        // 3. 处理 Bearer 前缀（Token 格式通常为 "Bearer xxx"）
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 4. 拼接 Redis Key，从 Redis 中获取用户信息
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);

        // 5. 验证用户信息是否存在（Token 可能已过期或无效）
        if (userMap == null || userMap.isEmpty()) {
            log.warn("Token 无效或已过期：token={}", token);
            return unauthorized(response);
        }

        // 6. 将 Redis 中的用户信息转换为 UserVO 对象
        // 安全取值，防止 Redis Hash 字段丢失导致 NPE
        String idStr = (String) userMap.get("id");
        String usernameStr = (String) userMap.get("username");
        if (!StringUtils.hasText(idStr) || !StringUtils.hasText(usernameStr)) {
            return unauthorized(response);
        }

        UserVO userVO = new UserVO();
        userVO.setId(Long.valueOf(idStr));
        userVO.setUsername(usernameStr);
        userVO.setNickname((String) userMap.getOrDefault("nickname", ""));
        userVO.setPhone((String) userMap.getOrDefault("phone", ""));
        userVO.setAvatar((String) userMap.getOrDefault("avatar", ""));
        String statusStr = (String) userMap.get("status");
        userVO.setStatus(StringUtils.hasText(statusStr) ? Integer.parseInt(statusStr) : 1);
        String roleStr = (String) userMap.get("role");
        userVO.setRole(StringUtils.hasText(roleStr) ? Integer.parseInt(roleStr) : 0);

        // 7. 将用户信息存入 ThreadLocal，方便后续 Controller/Service 使用
        UserHolder.saveUser(userVO);

        // 8. 刷新 Token 过期时间（滑动过期机制）
        stringRedisTemplate.expire(
                tokenKey,
                Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL)
        );

        log.info("Token 验证通过：userId={}, username={}", userVO.getId(), userVO.getUsername());

        // 9. 放行请求，继续执行后续的 Controller 方法
        return true;
    }

    /**
     * 请求完成后的回调方法
     * 在 Controller 方法执行完毕后调用
     * 用于清理 ThreadLocal 中的用户信息，防止内存泄漏
     */
    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        // 清理 ThreadLocal，避免线程池复用时数据残留
        UserHolder.removeUser();
        log.debug("请求完成，已清理 ThreadLocal");
    }

    /**
     * 返回未登录响应
     * 设置 HTTP 状态码为 401，并返回 JSON 格式的错误信息
     *
     * @param response HTTP 响应对象
     * @return 始终返回 false，表示拦截请求
     */
    private boolean unauthorized(HttpServletResponse response) throws Exception {
        // 设置 HTTP 状态码为 401（未授权）
        response.setStatus(401);
        // 设置响应内容类型为 JSON
        response.setContentType("application/json;charset=UTF-8");

        // 将 Result 对象转换为 JSON 字符串并写入响应
        String json = objectMapper.writeValueAsString(Result.error(401, "请先登录"));
        response.getWriter().write(json);

        // 返回 false，拦截请求
        return false;
    }
}
