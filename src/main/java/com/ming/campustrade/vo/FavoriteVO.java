package com.ming.campustrade.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FavoriteVO {

    private Long id;                // 收藏记录ID

    private Long productId;         // 商品ID

    private String productTitle;    // 商品标题

    private BigDecimal productPrice; // 商品价格

    private String productImage;    // 商品图片

    private Integer productStatus;  // 商品状态（用户可以看到商品是否还在售）

    private Long sellerId;          // 卖家ID

    private String sellerNickname;  // 卖家昵称

    private LocalDateTime createTime; // 收藏时间
}