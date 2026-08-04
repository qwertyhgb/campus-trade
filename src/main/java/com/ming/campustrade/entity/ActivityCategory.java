package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 活动分类实体类（Entity），与数据库 {@code activity_category} 表一一映射。
 *
 * <p>活动分类用于前台筛选和归类（学术讲座、体育竞技、文艺演出等），
 * 由管理员维护，一个活动只能属于一个分类（见 {@link Activity#getCategoryId()}）。</p>
 *
 * @author ming
 */
@Data
public class ActivityCategory {

    /** 分类主键 ID。 */
    private Long id;

    /** 分类名称，唯一（数据库有 uk_name 唯一索引）。 */
    private String name;

    /** 排序值，越小越靠前。 */
    private Integer sort;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
