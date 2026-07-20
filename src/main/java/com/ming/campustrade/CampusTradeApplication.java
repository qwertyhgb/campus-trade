package com.ming.campustrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 校园二手交易系统（Campus Trade）的 Spring Boot 启动类。
 *
 * <p>本类是整个应用的入口：运行 {@link #main(String[])} 方法即可启动内嵌的 Web 服务器
 * （默认 Tomcat）并初始化 Spring 容器，项目随即对外提供 HTTP 接口。</p>
 *
 * <p>{@link SpringBootApplication} 是一个"组合注解"，等价于同时标注了以下三个注解：</p>
 * <ul>
 *     <li>{@code @SpringBootConfiguration}（本质是 {@code @Configuration}）——
 *         声明本类是一个配置类，可以用 {@code @Bean} 方法向容器注册组件；</li>
 *     <li>{@code @EnableAutoConfiguration} —— 开启自动配置，Spring Boot 会根据 classpath
 *         中引入的依赖（如 MyBatis-Plus、Web）自动装配好相应的 Bean，省去大量手动 XML/Java 配置；</li>
 *     <li>{@code @ComponentScan} —— 自动扫描本类所在包（{@code com.ming.campustrade}）
 *         及其子包下带有 {@code @Component}、{@code @Service}、{@code @Controller}、
 *         {@code @Repository} 等注解的类，并把它们注册为 Spring 容器中的 Bean。</li>
 * </ul>
 *
 * <p>正因为 {@code @ComponentScan} 默认以启动类所在包为根，所以启动类必须放在所有业务代码的
 * 父包（这里是 {@code com.ming.campustrade}）下，否则子包中的组件会扫描不到。</p>
 *
 * @author ming
 */
@SpringBootApplication
public class CampusTradeApplication {

    /**
     * 应用程序入口方法。
     *
     * <p>{@link SpringApplication#run(Class, String...)} 会创建并启动 Spring 应用上下文，
     * 完成自动配置、组件扫描、内嵌服务器启动等一系列工作。传入 {@code CampusTradeApplication.class}
     * 是为了让框架知道自动配置与组件扫描的"主配置来源"。</p>
     *
     * @param args 命令行参数，可用于覆盖配置（如 {@code --server.port=9090}）
     */
    public static void main(String[] args) {
        SpringApplication.run(CampusTradeApplication.class, args);
    }
}
