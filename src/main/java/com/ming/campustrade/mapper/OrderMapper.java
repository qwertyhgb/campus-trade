package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.Order;

/**
 * 订单数据访问层（Mapper / DAO）。
 *
 * <p>{@link Mapper} 注解让 MyBatis 在启动时为本接口生成动态代理实现并注册为 Spring Bean，
 * 供 Service 层注入使用，无需手写实现类。</p>
 *
 * <p>继承 {@link BaseMapper} 后，MyBatis-Plus 自动提供针对 {@code order} 表的常用 CRUD 方法
 * （{@code insert}、{@code deleteById}、{@code updateById}、{@code selectById}、
 * {@code selectList} 等），无需编写 SQL 即可完成基础操作。
 * 注意：表名 {@code order} 是 MySQL 保留字，已在实体类 {@link Order} 上通过
 * {@code @TableName("`order`")} 用反引号处理，本接口无需额外配置。</p>
 *
 * @author ming
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
