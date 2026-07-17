package com.ming.campustrade.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductVO {

    private Long id;

    private String title;

    private String description;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private String image;

    private Long categoryId;

    private Long sellerId;

    private String sellerNickname;    // 卖家昵称

    private String sellerAvatar;      // 卖家头像

    private Integer conditionLevel;

    private Integer status;

    private Integer viewCount;

    private LocalDateTime createTime;
}