package com.ming.campustrade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {
    
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                    .info(new Info()
                        .title("校园二手交易平台 API")
                        .description("基于 Spring Boot 4.1 + Java 21 的校园二手交易平台接口文档")
                        .version("1.0.0")
                        .contact(new Contact()
                            .name("Ming")
                    )   
                );
    }
}
