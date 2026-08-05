package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.Notification;

/**
 * 站内通知数据访问层（Mapper / DAO），操作 {@code notification} 表。
 *
 * <p>继承 {@link BaseMapper} 即可获得单表常用 CRUD 能力，无需手写 SQL。
 * 通知模块的写入由消费者完成，用户查询和已读更新由 NotificationService 通过
 * LambdaQueryWrapper / LambdaUpdateWrapper 组合安全条件执行。</p>
 *
 * @author ming
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
