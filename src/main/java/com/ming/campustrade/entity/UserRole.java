package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 用户-角色关联实体类（Entity），与数据库 {@code user_role} 表一一映射。
 *
 * <p>RBAC 权限模型中的"多对多中间表"：连接 {@link User} 和 {@link Role}。
 * 一个用户可以拥有多个角色，一个角色也可以分配给多个用户。</p>
 *
 * <p>本表采用物理删除（无 deleted 字段）：因为存在联合唯一索引
 * (user_id, role_id)，若用逻辑删除，取消角色后记录残留会导致重新分配时唯一约束冲突。</p>
 *
 * @author ming
 */
@Data
public class UserRole {

    /** 主键 ID。 */
    private Long id;

    /** 用户 ID，关联 user 表。 */
    private Long userId;

    /** 角色 ID，关联 role 表。 */
    private Long roleId;

    /** 角色分配时间。 */
    private LocalDateTime createTime;
}
