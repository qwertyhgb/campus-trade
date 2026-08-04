package com.ming.campustrade.config;

import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 【类是什么】
 * 登录认证过滤器 —— 负责从 Token 中恢复当前用户身份。
 *
 * <p>【一句话职责】
 * 从请求头解析 Token → 查 Redis 拿用户信息 → 构建 Authentication 放进 SecurityContext。
 * 做完这三件事，Spring Security 的授权规则（authorizeHttpRequests）才知道"当前请求是谁"。</p>
 *
 * <p>【请求处理流程中的位置】
 * 浏览器请求 → Tomcat → Spring Security 过滤器链（我们在这里）
 *         → DispatcherServlet → Controller → Service → Mapper → 数据库
 *
 * 过滤器（Filter）在 Servlet 层，比拦截器（Interceptor）更早执行、范围更大。
 * 所有请求（包括静态资源）都会经过过滤器；拦截器只处理 Controller 方法。</p>
 *
 * <p>【认证 vs 授权（关键概念）】
 * - 认证（Authentication）：你是谁？—— 本过滤器只负责这一件事
 * - 授权（Authorization）：你能干什么？—— 交给 SecurityConfig 里的授权规则
 * 所以本过滤器 Token 无效时"什么都不做"，不在这里返回 401，
 * 而是不设置认证信息，让后面的授权规则统一决定：公开接口放行，受保护接口 401。</p>
 *
 * <p>【为什么继承 OncePerRequestFilter 而不是 implements Filter】
 * - implements Filter：请求内部发生 forward/include 时 doFilter 会被重复调用
 * - 继承 OncePerRequestFilter：内部用 request attribute 打标记，保证一个请求只执行一次
 * - 额外好处：可以重写 shouldNotFilter() 跳过某些路径（如登录接口）</p>
 */
@Slf4j
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Spring Data Redis 提供的操作模板。
     * 由 Spring Boot 自动配置注入，不用我们 new。
     * 泛型是 String（key 和 value 都是字符串），存取前需要手动转 String。
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 【核心方法】每个 HTTP 请求只经过这个方法一次（OncePerRequestFilter 保证）。
     *
     * <p>为什么用 try-finally 而不是 try-catch？</p>
     * <ul>
     *   <li>try：执行认证 + 放行请求（chain.doFilter 会把请求交给后面的过滤器/Controller）</li>
     *   <li>finally：不管认证成功还是失败、业务代码是否抛异常，都会执行清理</li>
     * </ul>
     *
     * <p>【为什么必须清理？—— 线程池复用的坑】
     * Tomcat 使用线程池处理请求，一个线程处理完请求后不会销毁，而是等着处理下一个请求。
     * SecurityContextHolder 和 UserHolder 内部都是 ThreadLocal（线程私有变量），
     * 如果不清理，下一个请求复用这个线程时，会拿到上一个用户的登录信息 —— 严重的安全漏洞！
     * （用户 A 的请求可能看到用户 B 的数据）</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        try {
            // 查工牌
            // 1. 尝试认证：Token 有效就设置 SecurityContext，无效就什么都不做
            //    注意：这里"什么都不做"是设计好的，不是忘了写 —— 认证失败的处理交给授权规则
            authenticate(request);

            // 放行
            // 2. 放行请求，继续执行过滤器链上后面的过滤器，最终到达 Controller
            //    这一步千万不能漏！漏了请求就断了，永远到不了 Controller
            chain.doFilter(request, response);
        } finally {
            // 清理
            // 3. 请求处理完（无论成功失败），清理两个上下文
            //    SecurityContextHolder：Spring Security 的"当前用户"容器
            //    UserHolder：我们自己封装的 ThreadLocal（旧业务代码还在用）
            SecurityContextHolder.clearContext();
            UserHolder.removeUser();
        }
    }

    /**
     * 【认证主流程】从请求头取 Token，查 Redis，构建认证信息放入 SecurityContext。
     *
     * <p>Redis 异常容错：如果 Redis 不可用，不抛异常，当作"未登录"处理。
     * 这样公开接口（如首页商品列表）不受影响，受保护接口由授权规则返回 401。</p>
     *
     * <p>【本方法所有 return 的共同含义】
     * "认证失败" 或 "不需要认证" —— 不往 SecurityContext 里放任何东西，
     * 后面的授权规则看到 SecurityContext 是空的，就会按"未登录"处理。</p>
     */
    private void authenticate(HttpServletRequest request) {
        // ================================================================
        // 第 1 步：从请求头取 Token
        // ================================================================
        // 前端登录成功后，后续每个请求都要在 Header 里带上 Token：
        //   Authorization: Bearer abc123...
        // 后端在这里把它取出来
        String token = request.getHeader("Authorization");

        // StringUtils.hasText()：判断字符串不是 null、不是空串、不是纯空白
        // 前端没带 Token → 不需要认证（可能是公开接口），直接返回
        if (!StringUtils.hasText(token)) {
            return;
        }
        // 处理 "Bearer xxx" 前缀：这是 Token 认证的通用格式约定
        // "Bearer" 的意思是"持有者"，即"持有此 Token 的人就是请求者本人"
        // substring(7) 是因为 "Bearer " 正好 7 个字符（B-e-a-r-e-r-空格）
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // ================================================================
        // 第 2 步：用 Token 查 Redis，获取用户信息
        // ================================================================
        // Redis Key 的拼接规则必须和登录接口写入时完全一致（见 RedisConstants）
        // 登录时：redis key = LOGIN_USER_KEY + token，value = 用户信息的 Hash
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

        // opsForHash()：操作 Redis 的 Hash 数据结构（相当于 Java 的 Map）
        // entries()：把整个 Hash 一次性取出来
        Map<Object, Object> userMap;
        try {
            userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        } catch (Exception e) {
            // Redis 挂了：不抛异常、不返回 401，只记日志当"未登录"
            // 让公开接口继续可用 —— 这叫"优雅降级"
            log.error("Redis 不可用，跳过 Token 认证：{}", e.getMessage());
            return;
        }

        // userMap 为空 = Redis 里没有这个 Token = Token 无效或已过期
        if (userMap == null || userMap.isEmpty()) {
            return;
        }

        // ================================================================
        // 第 3 步：安全取值（防止 Redis 数据缺失导致空指针）
        // ================================================================
        // Redis Hash 里的值都是 String 类型，需要强转
        // 防御性编程：id 或 username 缺失说明数据异常，直接当认证失败
        String idStr = (String) userMap.get("id");
        String usernameStr = (String) userMap.get("username");
        if (!StringUtils.hasText(idStr) || !StringUtils.hasText(usernameStr)) {
            return;
        }

        // ================================================================
        // 第 4 步：组装 UserVO（业务代码通过 UserHolder.getUserVO() 获取用户）
        // ================================================================
        // getOrDefault(key, 默认值)：字段不存在时给默认值，避免 NPE
        UserVO userVO = new UserVO();
        userVO.setId(Long.valueOf(idStr));
        userVO.setUsername(usernameStr);
        userVO.setNickname((String) userMap.getOrDefault("nickname", ""));
        userVO.setPhone((String) userMap.getOrDefault("phone", ""));
        userVO.setAvatar((String) userMap.getOrDefault("avatar", ""));
        // status 可能不存在（老数据），缺失时默认 1（正常），避免 parseInt(null) 空指针
        String statusStr = (String) userMap.get("status");
        userVO.setStatus(StringUtils.hasText(statusStr) ? Integer.parseInt(statusStr) : 1);
        String roleStr = (String) userMap.get("role");
        userVO.setRole(StringUtils.hasText(roleStr) ? Integer.parseInt(roleStr) : 0);

        // ================================================================
        // 第 5 步：封禁即时拦截
        // ================================================================
        // 为什么要单独查封禁标记？
        // 用户被封禁后，管理员会实时写入 login:disabled:{userId} 标记。
        // 而 Token Hash 里的 status 是登录时的快照（旧值可能还是 1），
        // 只靠快照无法立即生效 —— 封禁必须在下次请求就拦截！
        // 两个条件任一命中即视为封禁：
        //   ① Redis 里有封禁标记（管理员封禁时写入，实时生效）
        //   ② Token 快照里的 status == 0（兜底）
        String disabledKey = RedisConstants.LOGIN_DISABLED_KEY + userVO.getId();
        boolean isDisabled = Boolean.TRUE.equals(stringRedisTemplate.hasKey(disabledKey))
                || (userVO.getStatus() != null && userVO.getStatus() == 0);
        if (isDisabled) {
            log.warn("被封禁用户尝试访问，已清理token：userId={}", userVO.getId());
            // 顺手把 Redis 里的 Token 删掉（封禁 + 清理，防止滑动过期不断续期）
            stringRedisTemplate.delete(tokenKey);
            return; // 不设置认证信息 → 受保护接口返回 401，公开接口仍可访问
        }

        // ================================================================
        // 第 6 步：保存到 UserHolder（ThreadLocal）
        // ================================================================
        // 兼容旧业务代码：Service 层还在用 UserHolder.getUserVO() 拿用户
        // 后续迁移完成可以删除，届时统一从 SecurityContext 取
        UserHolder.saveUser(userVO);

        // ================================================================
        // 第 7 步：滑动过期 —— 用户活跃就续期
        // ================================================================
        // 设计思想：用户每次发请求都刷新 Token 的过期时间（如 30 分钟）
        // 效果：连续使用的用户不会掉线；长期不用的 Token 自动过期，防止长期有效被冒用
        // expire(key, duration)：重新设置过期时间
        stringRedisTemplate.expire(tokenKey, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

        // ================================================================
        // 第 8 步：维护"用户 → Token 集合"反向索引
        // ================================================================
        // 用途：管理员封禁用户时，需要找到该用户的所有 Token 并删除（强制下线）
        // Redis Set（无序集合）：SADD 幂等（重复添加同一个 token 无副作用）
        // TTL 同步刷新：保证反向索引和 Token 同时过期，避免索引残留
        String userTokensKey = RedisConstants.LOGIN_USER_TOKENS_KEY + userVO.getId();
        stringRedisTemplate.opsForSet().add(userTokensKey, token);
        stringRedisTemplate.expire(userTokensKey, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

        // ================================================================
        // 第 9 步：构建权限列表（角色 → Spring Security 权限）
        // ================================================================
        // Redis Hash 里存的是角色编码字符串："USER,ORGANIZER"
        // Spring Security 规定：角色必须带 "ROLE_" 前缀（如 ROLE_USER）
        // 后续授权规则用 hasRole("USER") 时，Security 自动加前缀去匹配
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        String rolesStr = (String) userMap.get("roles");
        if (StringUtils.hasText(rolesStr)) {
            for (String role : rolesStr.split(",")) {
                if (StringUtils.hasText(role)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim()));
                }
            }
        }

        // ================================================================
        // 第 10 步：构建 Authentication 放入 SecurityContext —— 认证完成的标志
        // ================================================================
        // UsernamePasswordAuthenticationToken 是 Spring Security 内置的
        // Authentication 实现类（虽然名字里有 UsernamePassword，但可以装任意主体）。
        // 三个参数：
        //   参数1 principal：认证主体，放 UserVO（业务代码可以从 SecurityContext 取用户）
        //   参数2 credentials：凭证（密码），这里用 null —— 我们不把密码放进 Redis
        //   参数3 authorities：权限列表，授权规则根据它判断"能干什么"
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userVO, null, authorities);

        // SecurityContextHolder：全局的"当前用户"容器（内部是 ThreadLocal）
        // getContext()：获取当前线程的 SecurityContext
        // setAuthentication()：把认证信息放进去 —— 这一步完成后，
        // Security 的授权过滤器才能识别"这个请求已登录"
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // debug 级别：每个请求都会经过这里，info 会在生产环境刷屏
        log.debug("Token 认证通过：userId={}, username={}, roles={}",
                userVO.getId(), userVO.getUsername(), rolesStr);
    }
}
