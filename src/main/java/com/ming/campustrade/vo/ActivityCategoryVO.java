package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 活动分类视图对象（VO，View Object），用于向前端返回活动分类数据。
 *
 * <p>相比 {@code ActivityCategory} 实体，屏蔽了内部字段，只暴露前端需要的内容。</p>
 *
 * @author ming
 */
@Data
public class ActivityCategoryVO {

    /** 分类 ID。 */
    private Long id;

    /** 分类名称。 */
    private String name;

    /** 排序值，越小越靠前。 */
    private Integer sort;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
