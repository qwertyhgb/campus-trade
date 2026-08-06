package com.ming.campustrade.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.annotation.Resource;

/**
 * 【类是什么】
 * Spring Security 核心配置类 —— 安全规则的“总指挥官”。
 *
 * <p>【它负责配置什么】</p>
 * <ul>
 *   <li>注册 TokenAuthenticationFilter：每次请求都先在这里认证（你是谁）</li>
 *   <li>注册 SecurityAccessHandler：未登录返回 401 JSON，无权限返回 403 JSON</li>
 *   <li>禁用 CSRF / Session / 表单登录 / HTTP Basic：纯 Token 认证的 REST API 不需要它们</li>
 *   <li>配置授权规则：哪些接口公开（permitAll），哪些必须登录（authenticated）</li>
 *   <li>提供 BCrypt 密码编码器（注册/登录时加密用）</li>
 * </ul>
 *
 * <p>【过滤器链上的执行顺序（关键）】
 * 请求到达时按顺序经过：
 * TraceIdFilter（追踪ID）→ RateLimitFilter（限流）→ TokenAuthenticationFilter（认证）→ 授权过滤器
 * 追踪 ID 在最前：让整条链共享同一个 traceId；限流其次：超限请求直接 429，不做认证；
 * 认证在前、授权在后，先知道“你是谁”，才能判断“你能干什么”。</p>
 */
@Configuration
@EnableWebSecurity
// 开启方法级安全：让 @PreAuthorize 注解在 Controller 方法上生效
// 有了它，权限控制可以在两个层面做：
//   1. URL 层面（authorizeHttpRequests）：按路径规则拦截
//   2. 方法层面（@PreAuthorize）：按方法精细控制（如 hasRole('ADMIN')）
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Token 认证过滤器：从请求头解析 Token → 查 Redis → 放入 SecurityContext。
     * 通过 addFilterBefore 插入过滤器链（见 securityFilterChain）。
     */
    @Resource
    private TokenAuthenticationFilter tokenAuthenticationFilter;

    /**
     * 限流过滤器：按 IP + 场景做固定窗口限流（登录、活动公开查询）。
     * 通过 addFilterBefore 插入到 TokenAuthenticationFilter 之前 ——
     * 限流必须最先执行：被限流的请求根本不需要做认证，省下 Redis/数据库开销。
     */
    @Resource
    private RateLimitFilter rateLimitFilter;

    /**
     * 请求追踪 ID 过滤器：为每个请求生成 traceId 写入 MDC 并返回响应头。
     * 插入到限流过滤器之前 —— 它是链路最前端，保证所有后续环节
     * （限流、认证、业务、审计日志）都带有同一个 traceId。
     */
    @Resource
    private TraceIdFilter traceIdFilter;

    /**
     * 401/403 响应处理器：未登录返回 {"code":401,"msg":"请先登录"}，
     * 无权限返回 {"code":403,...}，保证前端收到统一格式的 JSON。
     */
    @Resource
    private SecurityAccessHandler securityAccessHandler;

    /**
     * 安全过滤器链 —— Spring Security 的核心。
     *
     * 每个 HTTP 请求都会经过这条链上的一系列 Filter。
     * 我们在这里配置链的行为规则。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 1. 禁用 CSRF
            // CSRF 防护是针对"浏览器 Cookie 自动携带"这种认证方式的。
            // 我们用 Token 放在请求头里，浏览器不会自动携带，所以不存在 CSRF 攻击。
            // 如果不禁用，POST/PUT/DELETE 请求都会被拦截返回 403。
            .csrf(csrf -> csrf.disable())

            // 2. Session 策略设为无状态
            // 默认情况下 Spring Security 会用 HttpSession 保存登录态。
            // 但我们用 Redis 存 Token，不需要 Session。
            // STATELESS 告诉 Spring Security：不要创建 Session，不要从 Session 读登录态。
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // 把 TokenAuthenticationFilter 插到 UsernamePasswordAuthenticationFilter 前面
            // addFilterBefore(自定义过滤器, 参考过滤器)：自定义过滤器会在参考过滤器之前执行
            // 为什么插在这里？UsernamePasswordAuthenticationFilter 是 Security 内置的
            // 表单登录过滤器（已禁用），把它当作“位置锚点”：我们的认证逻辑要先于它执行
            .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

            // 把 RateLimitFilter 插到 TokenAuthenticationFilter 之前：
            // 限流是第一道门 —— 超限的请求直接 429，根本不需要认证
            .addFilterBefore(rateLimitFilter, TokenAuthenticationFilter.class)

            // 把 TraceIdFilter 插到限流过滤器之前：链路最前端，
            // 保证限流、认证、业务与审计日志都带有同一个 traceId
            .addFilterBefore(traceIdFilter, RateLimitFilter.class)

            // 定制 401/403 响应（Security 默认返回 302 跳转或 HTML，前端要 JSON）
            // authenticationEntryPoint：未登录访问受保护接口 → 401
            // accessDeniedHandler：已登录但权限不足 → 403
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(securityAccessHandler)
                .accessDeniedHandler(securityAccessHandler)
            )

            // 3. 禁用表单登录和 HTTP Basic 认证
            // Spring Security 默认会启用 formLogin（生成 /login 默认登录页）和 httpBasic。
            // 我们是纯 Token 认证的 REST API，这两种认证方式都用不上，
            // 不禁用会多出无用的过滤器，访问 /login 还会看到默认登录页。
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())

            // 4. 授权规则（从上到下依次匹配，命中第一个就生效，所以具体规则在前、兜底规则在后）
            .authorizeHttpRequests(auth -> auth
                // ===== 公开接口（不需要登录） =====

                // 用户模块：注册、登录（注册时还没有账号，登录时还没拿到 Token）
                .requestMatchers("/user/register", "/user/login").permitAll()

                // 商品模块：浏览列表、查看详情（GET 才公开；发布/修改/删除仍需登录）
                // 注意：requestMatchers(HttpMethod.GET, ...) 只对 GET 方法生效，
                // POST/PUT/DELETE 的同路径请求不会被这条规则放行，落到下面的 authenticated()
                // /product/my 是当前用户的私有数据，必须先于 /product/{id} 规则声明
                .requestMatchers(HttpMethod.GET, "/product/my", "/product/my/{id}").authenticated()
                .requestMatchers(HttpMethod.GET, "/product/list", "/product/{id}").permitAll()

                // 分类模块：查询公开（发布商品页要拉分类下拉框，可能还没登录）；增删改需要登录
                .requestMatchers(HttpMethod.GET, "/category/list", "/category/{id}").permitAll()

                // 活动分类模块：查询公开（发布活动页要拉分类下拉框）；增删改由 @PreAuthorize 控制
                .requestMatchers(HttpMethod.GET, "/activity-category/list").permitAll()

                // 活动模块：/activity/my 是当前用户私有数据，必须放在公开详情规则之前
                .requestMatchers(HttpMethod.GET, "/activity/my").authenticated()
                // 活动列表、详情和热门榜单公开，便于未登录用户浏览校园活动
                // 注意：/activity/hot 显式声明，避免依赖 /activity/{id} 的变量匹配规则
                .requestMatchers(HttpMethod.GET, "/activity/list", "/activity/{id}", "/activity/hot").permitAll()

                // 留言模块：看商品留言、看回复公开（浏览商品详情页就能看到留言）；发/删留言需要登录
                .requestMatchers(HttpMethod.GET, "/comment/product/{productId}",
                        "/comment/{parentId}/replies").permitAll()

                // ===== 静态资源 + 接口文档（Knife4j/Swagger）=====
                // 只有静态图片访问公开，POST /upload/image 上传操作仍需登录
                .requestMatchers(HttpMethod.GET, "/upload/**",
                        "/doc.html", "/webjars/**", "/v3/api-docs/**",
                        "/swagger-resources/**", "/favicon.ico", "/error").permitAll()

                // ===== 管理员后台：必须拥有 ADMIN 角色 =====
                // 双保险：URL 层面先拦截，方法层面还有 @PreAuthorize 控制
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // ===== 活动模块：创建、编辑、提交审核 =====
                // ORGANIZER 可以管理自己创建的活动，ADMIN 作为系统管理员保留全局操作权限；
                // 具体活动归属和状态仍由 ActivityService 二次校验。
                .requestMatchers("/activity/create", "/activity/update")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/activity/{id}")
                        .hasAnyRole("ORGANIZER", "ADMIN")
                .requestMatchers("/activity/*/submit-review")
                        .hasAnyRole("ORGANIZER", "ADMIN")

                // ===== 活动模块：审核和下架 =====
                // AUDITOR 负责审核，ADMIN 也具备审核权限；下架属于管理员高风险操作。
                .requestMatchers("/activity/review")
                        .hasAnyRole("AUDITOR", "ADMIN")
                .requestMatchers("/activity/*/off-shelf")
                        .hasRole("ADMIN")

                // ===== 预约模块：全部接口需登录 =====
                // 显式声明让规则更清晰（anyRequest().authenticated() 也会兜底）；
                // 组织者查看预约名单的角色校验由 @PreAuthorize 负责，Service 层再校验归属
                .requestMatchers("/reservation/**").authenticated()

                // ===== 通知模块：全部接口需登录 =====
                // 通知内容包含用户预约信息、活动审核结果等隐私数据，不能公开
                .requestMatchers("/notification/**").authenticated()

                // ===== 兜底规则：其余所有接口必须登录 =====
                // 注意：角色校验由 @PreAuthorize 和 /admin/** 的 URL 规则共同负责
                .anyRequest().authenticated()
            );

        return http.build();
    }

    /**
     * 关键：禁用过滤器的 Servlet 自动注册。
     *
     * <p>TokenAuthenticationFilter 是 @Component，Spring Boot 默认会把所有 Filter Bean
     * 自动注册进 Servlet 容器。而它又通过 addFilterBefore 加入了 Security 过滤器链，
     * 如果不禁用，同一个请求会执行两次 doFilter（虽然第二次是幂等的，但浪费性能且容易出诡异 bug）。
     * 📝 记住这个坑：凡是 @Component 的 Filter 又通过 addFilterBefore 加入 Security 链的，
     * 必须 写这个 FilterRegistrationBean 禁用自动注册！面试也爱问！🔥</p>
     */
    @Bean
    public FilterRegistrationBean<TokenAuthenticationFilter> tokenFilterRegistration(
            TokenAuthenticationFilter filter) {
        FilterRegistrationBean<TokenAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 同样禁用 RateLimitFilter 的 Servlet 自动注册。
     *
     * <p>它与 TokenAuthenticationFilter 情况相同：既是 @Component（会被 Spring Boot
     * 自动注册进 Servlet 容器），又通过 addFilterBefore 加入了 Security 过滤器链。
     * 如果不禁用自动注册，同一个请求会被执行两次 doFilterInternal ——
     * 限流计数会翻倍，正常用户可能被提前误杀。必须 setEnabled(false)。</p>
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 同样禁用 TraceIdFilter 的 Servlet 自动注册。
     *
     * <p>与另外两个过滤器相同：既是 @Component 又加入了 Security 链，
     * 不禁用自动注册会被执行两次 —— traceId 被重复生成、
     * MDC 清理时机错乱，必须 setEnabled(false)。</p>
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(
            TraceIdFilter filter) {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * BCrypt 密码编码器（保留原来的）。
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
