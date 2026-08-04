package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.Role;

/**
 * 角色数据访问层（Mapper / DAO），操作 {@code role} 表。
 *
 * <p>继承 {@link BaseMapper} 即可获得单表常用 CRUD 能力，无需手写 SQL。</p>
 *
 * @author ming
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
