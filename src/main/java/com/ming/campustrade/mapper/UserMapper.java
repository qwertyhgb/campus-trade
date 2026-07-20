package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.User;

/**
 * 用户数据访问层（Mapper / DAO）。
 *
 * <p>{@link Mapper} 注解的作用：告诉 MyBatis 在启动时为本接口生成动态代理实现类，
 * 并把它注册成 Spring 容器中的 Bean，这样 Service 层就能通过 {@code @Autowired}
 * 直接注入使用，而不需要我们手写实现类。</p>
 *
 * <p>继承 {@link BaseMapper} 的好处：MyBatis-Plus 已经内置了针对单表的常用 CRUD 方法
 * （如 {@code insert}、{@code deleteById}、{@code updateById}、{@code selectById}、
 * {@code selectList} 等），无需编写任何 SQL 或 XML 即可完成基础增删改查。
 * 只有遇到复杂的多表查询、统计等需求时，才需要自己额外定义方法并编写 SQL。</p>
 *
 * <p>泛型 {@link User} 指定了本 Mapper 操作的实体类型，框架据此推断对应的表名和字段映射。</p>
 *
 * @author ming
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
