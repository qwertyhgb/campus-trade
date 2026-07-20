package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 商品分类视图对象（VO，View Object），用于向前端返回分类数据。
 *
 * <p>相比 {@code Category} 实体，本 VO 屏蔽了 {@code deleted}、{@code updateTime}
 * 等前端无需关心的内部字段，只返回展示分类导航所需的信息，
 * 起到数据过滤与接口解耦的作用。</p>
 *
 * @author ming
 */
@Data
public class CategoryVO {

    /** 分类主键 ID。 */
    private Long id;

    /** 分类名称，例如 "数码产品"。 */
    private String name;

    /** 分类图标 URL 或图标标识。 */
    private String icon;

    /** 排序权重，数值越大越靠前。 */
    private Integer sort;

    /** 分类状态：1=启用，0=禁用。 */
    private Integer status;

    /** 分类创建时间。 */
    private LocalDateTime createTime;
}
