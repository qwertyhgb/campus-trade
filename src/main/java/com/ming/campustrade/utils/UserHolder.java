package com.ming.campustrade.utils;

import com.ming.campustrade.vo.UserVO;

/**
 * 用户上下文（基于 ThreadLocal）
 *
 * <p>使用场景：在 TokenAuthenticationFilter（认证过滤器）中把当前登录用户信息存入当前线程，
 * 业务方法中可通过 {@link #getUserVO()} 直接获取当前登录用户，
 * 避免在 Controller / Service 方法签名中层层传递 userId 或 user 对象。</p>
 *
 * <p><b>为什么用 ThreadLocal？</b><br>
 * Web 请求由 Tomcat 工作线程处理，每个请求独占一个线程，
 * ThreadLocal 可以在同一线程内任意位置存取数据，天然线程隔离。</p>
 *
 * <p><b>使用规范：</b><br>
 * ① 在过滤器（TokenAuthenticationFilter）中调用 {@link #saveUser(UserVO)} 存放用户<br>
 * ② 在 finally 块中调用 {@link #removeUser()} 清理（必须！防止线程复用导致内存泄漏）<br>
 * ③ 在 Controller / Service 中调用 {@link #getUserVO()} 获取当前登录用户</p>
 *
 * <p><b>典型调用链：</b></p>
 * <pre>{@code
 * // 1. 过滤器（TokenAuthenticationFilter）中
 * UserHolder.saveUser(currentUser);
 * try {
 *     // 放行业务
 *     return true;
 * } finally {
 *     UserHolder.removeUser();
 * }
 *
 * // 2. 业务方法中
 * UserVO user = UserHolder.getUserVO();
 * if (user == null) {
 *     throw new BusinessException(ResultCode.UNAUTHORIZED);
 * }
 * Long userId = user.getId();
 * }</pre>
 */
public class UserHolder {

    /**
     * 当前线程的用户存储容器
     * <p>每个 HTTP 请求对应一个独立线程，ThreadLocal 保证线程间数据隔离</p>
     */
    private static final ThreadLocal<UserVO> USER_THREAD_LOCAL = new ThreadLocal<>();

    /**
     * 保存当前登录用户到当前线程
     *
     * <p>通常在 TokenAuthenticationFilter 中调用，业务方法中无需关心</p>
     *
     * @param userVO 当前登录用户信息（从 Redis / Session 中解析得到）
     */
    public static void saveUser(UserVO userVO) {
        USER_THREAD_LOCAL.set(userVO);
    }

    /**
     * 获取当前线程中保存的登录用户
     *
     * <p>通常在 Controller / Service 中调用，调用前无需判空<br>
     * 若返回 null 说明：① 未登录被拦截 ② 过滤器未配置 ③ 过滤器未调用 saveUser</p>
     *
     * @return 当前登录用户，未登录返回 null
     */
    public static UserVO getUserVO() {
        return USER_THREAD_LOCAL.get();
    }

    /**
     * 清理当前线程中的用户信息
     *
     * <p><b>必须</b>在 finally 块中调用！<br>
     * Tomcat 线程池会复用线程，如果不清理，下一个请求可能拿到上一个用户的残留数据（严重的内存泄漏 + 数据串号问题）</p>
     */
    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }
}
