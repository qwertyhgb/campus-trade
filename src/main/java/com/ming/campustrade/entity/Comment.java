package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Data;

/**
 * 商品留言实体类（Entity），与数据库 {@code comment} 表一一映射。
 *
 * <p>本表存储用户对商品的留言/提问及其回复。通过 {@code parentId} 实现两级结构：
 * 顶级留言（parentId = null）和回复（parentId = 被回复留言的 ID）。
 * 这种"邻接表"模型简单直观，适合校园平台留言这种层级不深的场景。</p>
 *
 * <p>{@code replyToUserId} 是反范式化的冗余字段：理论上通过 parentId JOIN 父留言
 * 就能拿到被回复者，但每次展示"回复 @xxx"都做 JOIN 成本太高。冗余存储后，
 * 查询时直接读取即可，用少量存储空间换取查询性能。</p>
 *
 * @author ming
 */
@Data
public class Comment {

    /**
     * 留言主键 ID，对应表中 {@code id} 列。
     */
    private Long id;

    /**
     * 关联的商品 ID，指向 {@code product} 表主键，标识这条留言是留给哪件商品的。
     */
    private Long productId;

    /**
     * 留言用户 ID，指向 {@code user} 表主键，标识谁发了这条留言。
     */
    private Long userId;

    /**
     * 留言内容，最长 500 字符。
     */
    private String content;

    /**
     * 父留言 ID（自引用外键）。
     *
     * <ul>
     *     <li>{@code null} —— 顶级留言（直接对商品发表的提问/评论）</li>
     *     <li>非 {@code null} —— 回复某条留言（值为被回复留言的 ID）</li>
     * </ul>
     */
    private Long parentId;

    /**
     * 被回复的用户 ID。
     *
     * <p>当本条留言是"回复"时，记录被回复者的用户 ID，
     * 用于前端展示 "回复 @xxx" 的效果。顶级留言时为 {@code null}。</p>
     */
    private Long replyToUserId;

    /**
     * 留言时间，使用 {@link LocalDateTime}（线程安全、API 友好）。
     */
    private LocalDateTime createTime;

    /**
     * 最近一次更新时间（编辑留言时自动刷新）。
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志位（0=未删除，1=已删除）。
     *
     * <p>{@link TableLogic} 让 MyBatis-Plus 把删除操作转换为
     * {@code UPDATE ... SET deleted = 1}，并自动在所有查询中追加
     * {@code WHERE deleted = 0}，过滤已删除的留言。</p>
     */
    @TableLogic
    private Integer deleted;
}
