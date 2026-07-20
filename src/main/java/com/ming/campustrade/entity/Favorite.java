package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 收藏表实体类，对应数据库 favorite 表
 *
 * 注意：收藏表采用「物理删除」，没有 deleted 字段和 @TableLogic 注解。
 * 原因：数据库有唯一索引 uk_user_product(user_id, product_id)，
 * 如果使用逻辑删除，取消收藏后记录仍留在表中（deleted=1），
 * 再次收藏时 INSERT 会触发唯一约束冲突，导致收藏静默失败。
 * 物理删除后记录真正从表中移除，再次收藏可以正常写入。
 */
@Data
public class Favorite {

    /** 收藏记录 ID（主键，自增） */
    private Long id;

    /** 用户 ID（谁收藏的） */
    private Long userId;

    /** 商品 ID（收藏了哪个商品） */
    private Long productId;

    /** 收藏时间（由数据库 DEFAULT CURRENT_TIMESTAMP 自动填充） */
    private LocalDateTime createTime;
}
