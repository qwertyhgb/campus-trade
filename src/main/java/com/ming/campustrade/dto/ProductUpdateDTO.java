package com.ming.campustrade.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductUpdateDTO {

    @Size(max = 100, message = "商品标题不能超过100个字符")
    private String title;

    @Size(max = 1000, message = "商品描述不能超过1000个字符")
    private String description;

    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    @DecimalMin(value = "0.01", message = "原价必须大于0")
    private BigDecimal originalPrice;

    private String image;

    private Long categoryId;

    private Integer conditionLevel;
}