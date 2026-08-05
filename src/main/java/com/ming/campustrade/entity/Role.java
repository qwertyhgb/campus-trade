package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 角色实体类（Entity），与数据库 {@code role} 表一一映射。
 *
 * <p>RBAC 权限模型中的"角色定义表"：存储系统所有角色（USER、ADMIN 等）。
 * 用户与角色是多对多关系，通过 {@link UserRole} 关联表连接。</p>
 *
 * @author ming
 */
@Data
public class Role {

    /** 角色主键 ID。 */
    private Long id;

    /**
     * 角色编码（英文标识），如 USER、ADMIN。
     * 用于程序判断权限，唯一不可重复。
     */
    private String roleCode;

    /** 角色名称（中文显示），如 普通用户、管理员。 */
    private String roleName;

    /** 角色描述。 */
    private String description;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
