package com.ming.campustrade.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 *
 * <p><b>这个类的作用：</b>注册 MyBatis-Plus 的分页插件（PaginationInnerInterceptor）。
 * 如果不配置这个类，当你传入 Page(1, 10) 进行分页查询时，MyBatis-Plus <b>不会</b>自动在 SQL 末尾追加
 * LIMIT 语句，而是把整张表的数据全部查出来，分页参数形同虚设。</p>
 *
 * <p><b>工作原理：</b></p>
 * <pre>{@code
 * 你写的代码:   productMapper.selectPage(new Page<>(1, 10), wrapper);
 * MyBatis-Plus 原始 SQL:  SELECT * FROM product WHERE status = 1
 * 分页拦截器改写后:       SELECT * FROM product WHERE status = 1 LIMIT 10
 * }</pre>
 *
 * <p>拦截器在 SQL 真正执行前介入，用 JSQLParser 解析原始 SQL，
 * 自动在末尾拼接 LIMIT 子句，同时执行一次 COUNT 查询获取总记录数。</p>
 *
 * <p><b>为什么需要单独引入 mybatis-plus-jsqlparser 依赖？</b><br>
 * 从 MyBatis-Plus 3.5.4 开始，分页拦截器依赖的 JSQLParser（SQL 解析库）
 * 被拆分到独立的 mybatis-plus-jsqlparser 模块中，starter 不再自动包含。
 * 如果 pom.xml 中没有这个依赖，PaginationInnerInterceptor 类将无法解析（编译报错）。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 全局拦截器 Bean
     *
     * <p>MybatisPlusInterceptor 是一个"外层拦截器"，它本身不做具体的事，
     * 而是一个容器，可以往里面添加多个"内部拦截器"（InnerInterceptor）。
     * 每个内部拦截器负责一个具体功能，比如分页、防全表更新、数据权限等。</p>
     *
     * <p>Spring Boot 启动时会扫描到 @Bean 注解，把这个拦截器注册到 Spring 容器中。
     * MyBatis-Plus 在初始化时会从容器中找到所有 MybatisPlusInterceptor 类型的 Bean，
     * 自动挂载到 SqlSessionFactory 上，对所有 SQL 执行生效。</p>
     *
     * @return 配置好的 MybatisPlusInterceptor 实例
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 1. 创建外层拦截器（容器）
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 2. 创建分页内部拦截器，指定数据库类型为 MySQL
        //    DbType 枚举决定了拦截器生成哪种方言的 LIMIT 语法：
        //    - DbType.MYSQL  → LIMIT 0, 10
        //    - DbType.ORACLE → ROWNUM BETWEEN 1 AND 10（Oracle 没有 LIMIT 关键字）
        //    - DbType.POSTGRE_SQL → LIMIT 10 OFFSET 0
        //    我们用的是 MySQL，所以传 DbType.MYSQL
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);

        // 3. 将分页拦截器加入外层拦截器
        //    如果以后需要其他功能（如防全表更新），可以继续 addInnerInterceptor：
        //    interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        interceptor.addInnerInterceptor(paginationInterceptor);

        return interceptor;
    }
}
