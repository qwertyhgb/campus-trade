package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.OperationLog;

/**
 * 操作审计日志数据访问层，操作 {@code operation_log} 表。
 *
 * <p>继承 {@link BaseMapper} 即可获得单表 CRUD 与分页查询能力；
 * 本表是审计数据，只写入与查询，不允许业务代码修改或删除历史记录。</p>
 *
 * @author ming
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
