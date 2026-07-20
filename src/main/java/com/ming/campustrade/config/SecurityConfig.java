package com.ming.campustrade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 安全配置类 —— 仅提供密码加密器，并非完整的 Spring Security 配置。
 *
 * <h2>为什么需要这个类？</h2>
 * <p>
 * 用户注册时，我们不能把密码明文存进数据库（一旦数据库泄露，所有用户密码就暴露了）。
 * 所以需要对密码进行「单向哈希」处理：注册时加密存储，登录时把用户输入的密码也加密一遍，
 * 然后比较两个密文是否一致。
 * </p>
 *
 * <h2>为什么选择 BCrypt？</h2>
 * <ul>
 *   <li><b>自带随机盐（Salt）</b>：每次加密同一个密码，生成的密文都不同，
 *       攻击者无法用「彩虹表」（预先计算好的 密码→密文 对照表）来反查密码。</li>
 *   <li><b>自适应（Adaptive）</b>：可以通过 cost 参数（默认 10）控制计算复杂度，
 *       硬件越强就把 cost 调高，让暴力破解始终需要足够时间。</li>
 *   <li><b>业界标准</b>：Spring Security 官方推荐的密码编码方案。</li>
 * </ul>
 *
 * <h2>注意：这不是完整的 Spring Security</h2>
 * <p>
 * 本项目没有引入 {@code SecurityFilterChain}（即没有启用 Spring Security 的过滤器链），
 * 权限控制是通过自定义拦截器（{@code LoginInterceptor} + {@code RoleInterceptor}）实现的。
 * 这里只是借用 Spring Security 提供的 {@link BCryptPasswordEncoder} 工具类来做密码加密。
 * </p>
 *
 * <h2>核心注解说明</h2>
 * <ul>
 *   <li>{@code @Configuration}：告诉 Spring「这是一个配置类」，相当于一个 XML 配置文件。
 *       Spring 启动时会扫描并加载这个类中定义的所有 Bean。</li>
 *   <li>{@code @Bean}：标注在方法上，表示「这个方法的返回值要注册到 Spring 容器中」。
 *       之后在其他地方需要 {@code BCryptPasswordEncoder} 时，Spring 会自动注入这个实例，
 *       而不需要手动 {@code new}。</li>
 * </ul>
 *
 * @author Ming
 * @since 1.0.0
 */
@Configuration
public class SecurityConfig {

    /**
     * 创建 BCrypt 密码编码器并注册为 Spring Bean。
     *
     * <p>
     * 使用方式：在 Service 层通过构造器注入 {@code BCryptPasswordEncoder}，
     * 然后调用 {@code encode(rawPassword)} 加密、{@code matches(rawPassword, encodedPassword)} 校验。
     * </p>
     *
     * @return BCryptPasswordEncoder 实例（默认 cost=10，即 2^10=1024 轮迭代）
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
