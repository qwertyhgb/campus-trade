package com.ming.campustrade.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 兼容配置。
 *
 * <p>项目当前使用 Spring Boot 4。Spring Boot 4 的 Web 层默认使用 Jackson 3，
 * 但项目中已有商品缓存、安全错误响应等旧代码仍然明确依赖
 * {@code com.fasterxml.jackson.databind.ObjectMapper}（Jackson 2）。
 * 两个 ObjectMapper 属于不同的 Java 类型，Spring 不会把 Jackson 3 的 Bean
 * 自动当成 Jackson 2 注入，所以原来启动测试时会出现“找不到 ObjectMapper Bean”。</p>
 *
 * <p>这里保留旧代码使用的 Jackson 2，并显式注册为 Bean。这样可以让现有代码平稳运行，
 * 后续如果把所有旧代码迁移到 Jackson 3，再删除本配置和 pom.xml 中的 Jackson 2 依赖即可。
 * {@code findAndRegisterModules()} 会自动发现 JavaTimeModule，保证 LocalDateTime
 * 等时间类型可以正常写入商品缓存。</p>
 */
@Configuration
public class Jackson2CompatibilityConfig {

    /**
     * 提供旧代码需要的 Jackson 2 序列化器。
     *
     * <p>方法名特意带上 jackson2，方便新手阅读项目时区分它和 Spring Boot 4
     * 内部使用的 Jackson 3 ObjectMapper。</p>
     */
    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        return objectMapper;
    }
}
