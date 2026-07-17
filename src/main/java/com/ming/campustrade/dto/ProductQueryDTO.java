package com.ming.campustrade.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductQueryDTO {

    private Integer pageNo = 1;       // 页码，默认第1页

    private Integer pageSize = 10;    // 每页条数，默认10条

    private String keyword;           // 搜索关键词（模糊匹配标题）

    private Long categoryId;          // 分类筛选

    private BigDecimal minPrice;      // 最低价

    private BigDecimal maxPrice;      // 最高价

    private Integer conditionLevel;   // 成色筛选

    private String sort;              // 排序方式：latest/price_asc/price_desc
}