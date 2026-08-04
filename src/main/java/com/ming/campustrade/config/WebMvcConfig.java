package com.ming.campustrade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类
 * 用于配置静态资源等 Spring MVC 相关组件
 * 
 * @author Ming
 * @since 2026-07-01
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 图片存储目录（从配置文件读取，如 D:/campus-trade-uploads）
     */
    @Value("${upload.path}")
    private String uploadPath;

    /**
     * 图片访问的 URL 前缀（从配置文件读取，如 /upload）
     */
    @Value("${upload.url-prefix}")
    private String urlPrefix;

    /**
     * 配置静态资源映射
     *
     * <p>把 URL 路径 /upload/** 映射到磁盘目录 D:/campus-trade-uploads/，
     * 这样浏览器访问 http://localhost:8080/upload/abc.jpg 就能直接看到上传的图片。</p>
     *
     * <p>图片资源不经过 Controller 方法，由 Spring MVC 静态资源处理器直接映射到磁盘目录。</p>
     *
     * @param registry 资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // addResourceHandler：指定哪些 URL 路径由静态资源处理器处理
        // addResourceLocations：指定去磁盘哪个目录找文件，file: 前缀表示文件系统路径
        registry.addResourceHandler(urlPrefix + "/**")
                .addResourceLocations("file:" + uploadPath + "/");
    }
}
