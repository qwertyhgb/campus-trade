package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Data;

/**
 * 商品分类实体类（Entity），与数据库 {@code category} 表一一映射。
 *
 * <p>分类用于对商品进行归类（如 "数码产品"、"图书教材"、"生活用品" 等），
 * 方便买家按类别筛选浏览。商品表通过 {@code categoryId} 关联到本表。</p>
 *
 * @author ming
 */
@Data
public class Category {

    /**
     * 分类主键 ID，对应表中 {@code id} 列。
     */
    private Long id;

    /**
     * 分类名称，例如 "数码产品"，展示在前台分类导航上。
     */
    private String name;

    /**
     * 分类图标的 URL 或图标标识，用于在界面上展示对应图标。
     */
    private String icon;

    /**
     * 排序权重，数值越大越靠前。
     *
     * <p>查询分类列表时通常按 {@code sort DESC} 排序，权重高的分类排在前面，
     * 运营人员可借此把热门分类置顶。</p>
     */
    private Integer sort;

    /**
     * 分类状态。
     *
     * <ul>
     *     <li>{@code 1} —— 启用（前台可见、可选）</li>
     *     <li>{@code 0} —— 禁用（前台隐藏，但数据保留）</li>
     * </ul>
     */
    private Integer status;

    /**
     * 分类创建时间，使用 {@link LocalDateTime}（线程安全、API 友好）。
     */
    private LocalDateTime createTime;

    /**
     * 分类最近一次更新时间。
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志位（0=未删除，1=已删除）。
     *
     * <p>{@link TableLogic} 让删除操作转换为 {@code UPDATE ... SET deleted = 1}，
     * 并在查询时自动过滤。这样删除分类不会破坏已有商品的 {@code categoryId} 关联。</p>
     */
    @TableLogic
    private Integer deleted;
}
