package com.ming.campustrade.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.UserRole;

/**
 * 用户-角色关联数据访问层（Mapper / DAO），操作 {@code user_role} 表。
 *
 * <p>除了继承 {@link BaseMapper} 的单表 CRUD 能力外，
 * 额外提供一个跨表查询方法：查出某用户拥有的所有角色编码。</p>
 *
 * @author ming
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * 查询某用户拥有的所有角色编码列表。
     *
     * <p>需要跨表 JOIN：user_role 表只存了 role_id，角色编码（USER、ADMIN）
     * 存在 role 表的 role_code 字段，所以要关联 role 表才能拿到编码。</p>
     *
     * <p>等价 SQL：</p>
     * <pre>{@code
     * SELECT r.role_code
     * FROM user_role ur
     * JOIN role r ON ur.role_id = r.id
     * WHERE ur.user_id = ?
     * }</pre>
     *
     * @param userId 用户 ID
     * @return 角色编码列表，如 ["USER"] 或 ["USER", "ADMIN"]；无角色时返回空列表
     */
    @Select("SELECT r.role_code FROM user_role ur " +
            "JOIN role r ON ur.role_id = r.id " +
            "WHERE ur.user_id = #{userId}")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
