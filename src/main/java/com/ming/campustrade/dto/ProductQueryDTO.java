package com.ming.campustrade.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 商品查询数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于封装前台商品列表页的"分页 + 多条件筛选 + 排序"查询参数。
 * 把这些零散的查询条件收敛到一个对象里，可以让 Controller 方法签名更简洁，
 * 也便于后续扩展新的筛选条件而无需改动方法参数列表。</p>
 *
 * <p>所有字段均为可选：前端不传某条件时，Service 层即不对该条件做过滤，
 * 从而灵活组合出各种查询场景（如只按分类筛选、只按价格区间筛选等）。</p>
 *
 * @author ming
 */
@Data
public class ProductQueryDTO {

    /** 页码，默认第 1 页。 */
    private Integer pageNo = 1;

    /** 每页条数，默认 10 条。 */
    private Integer pageSize = 10;

    /** 搜索关键词（模糊匹配标题）。 */
    private String keyword;

    /** 分类筛选。 */
    private Long categoryId;

    /** 最低价。 */
    private BigDecimal minPrice;

    /** 最高价。 */
    private BigDecimal maxPrice;

    /** 成色筛选。 */
    private Integer conditionLevel;

    /** 排序方式：latest / price_asc / price_desc。 */
    private String sort;
}
