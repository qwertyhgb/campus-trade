package com.ming.campustrade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI（Swagger）接口文档配置类。
 *
 * <h2>什么是 OpenAPI / Swagger？</h2>
 * <p>
 * 在前后端分离开发中，后端写完接口后，前端需要知道：
 * 「有哪些接口？每个接口要传什么参数？返回什么格式？」
 * 如果每次都口头沟通或写 Word 文档，效率很低且容易过时。
 * </p>
 * <p>
 * <b>OpenAPI</b>（前身叫 Swagger）是一套「接口文档自动生成」规范。
 * 只要你在 Controller 方法上加好注解（如 {@code @Operation}、{@code @Parameter}），
 * 框架就能自动扫描所有接口，生成一份在线的、可交互的 API 文档。
 * </p>
 *
 * <h2>如何访问接口文档？</h2>
 * <p>
 * 本项目集成了 <b>Knife4j</b>（Swagger 的增强 UI），启动项目后访问：
 * </p>
 * <pre>
 *   http://localhost:8080/doc.html
 * </pre>
 * <p>
 * 即可看到所有接口的分组列表，还能直接在页面上填参数、发请求、看响应（类似 Postman）。
 * </p>
 *
 * <h2>这个配置类做了什么？</h2>
 * <p>
 * 默认的 Swagger 文档标题是「API」，没有任何描述信息。
 * 这个类通过注册一个自定义的 {@link OpenAPI} Bean，来定制文档的：
 * </p>
 * <ul>
 *   <li>标题（title）</li>
 *   <li>描述（description）</li>
 *   <li>版本号（version）</li>
 *   <li>联系人信息（contact）</li>
 * </ul>
 * <p>
 * 这样打开 doc.html 时，页面顶部就会显示我们自定义的项目信息，而不是默认的空标题。
 * </p>
 *
 * @author Ming
 * @since 1.0.0
 */
@Configuration
public class OpenApiConfig {

    /**
     * 自定义 OpenAPI 文档的全局元信息。
     *
     * <p>
     * Spring Boot 启动时，springdoc 库会自动查找容器中的 {@link OpenAPI} Bean，
     * 并用它来渲染文档页面的标题、描述等头部信息。
     * </p>
     *
     * @return 配置好标题、描述、版本、联系人的 OpenAPI 实例
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        // 文档标题：显示在 doc.html 页面最顶部
                        .title("校园二手交易平台 API")
                        // 文档描述：对项目的简要介绍，帮助阅读者快速了解这是什么系统
                        .description("基于 Spring Boot 4.1 + Java 21 的校园二手交易平台接口文档")
                        // 版本号：方便前后端对齐「当前对接的是哪个版本的接口」
                        .version("1.0.0")
                        // 联系人：出问题时前端同学知道该找谁
                        .contact(new Contact()
                                .name("Ming")
                        )
                );
    }
}
