package com.ming.campustrade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类
 * 用于配置拦截器、视图解析器、消息转换器等 Spring MVC 相关组件
 * 
 * @author Ming
 * @since 2026-07-01
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 登录拦截器
     * 用于验证用户是否登录，未登录则拒绝访问
     */
    private LoginInterceptor loginInterceptor;
    
    /**
     * 角色权限拦截器
     * 用于验证用户角色权限，检查是否有足够权限访问接口
     */
    private RoleInterceptor roleInterceptor;

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
     * 构造函数
     * 通过依赖注入的方式初始化拦截器
     * 
     * @param loginInterceptor 登录拦截器实例
     * @param roleInterceptor 角色权限拦截器实例
     */
    public WebMvcConfig(LoginInterceptor loginInterceptor, RoleInterceptor roleInterceptor) {
        this.loginInterceptor = loginInterceptor;
        this.roleInterceptor = roleInterceptor;
    }

    /**
     * 配置拦截器
     * 注册自定义拦截器并指定拦截路径和排除路径
     * 
     * 拦截器执行顺序：
     * 1. loginInterceptor (order=1) - 首先执行登录验证
     * 2. roleInterceptor (order=2) - 登录验证通过后再执行角色权限验证
     * 
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册登录拦截器
        // 拦截所有请求，但排除用户注册和登录接口
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")  // 拦截所有请求路径
                .excludePathPatterns(     // 排除以下路径，不进行拦截
                        "/user/register",  // 用户注册接口
                        "/user/login"      // 用户登录接口
                )
                .order(1);  // 设置拦截器执行顺序，值越小越先执行

        // 注册角色权限拦截器
        // 在登录验证通过后，进一步验证用户角色权限
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/**")  // 拦截所有请求路径
                .excludePathPatterns(     // 排除以下路径，不进行拦截
                        "/user/register",  // 用户注册接口
                        "/user/login"      // 用户登录接口
                )
                .order(2);  // 设置拦截器执行顺序，在登录拦截器之后执行
    }

    /**
     * 配置静态资源映射
     *
     * <p>把 URL 路径 /upload/** 映射到磁盘目录 D:/campus-trade-uploads/，
     * 这样浏览器访问 http://localhost:8080/upload/abc.jpg 就能直接看到上传的图片。</p>
     *
     * <p>这些请求不是 Controller 方法，LoginInterceptor 和 RoleInterceptor 中
     * handler instanceof HandlerMethod 判断为 false 会直接放行，所以图片可以公开访问。</p>
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