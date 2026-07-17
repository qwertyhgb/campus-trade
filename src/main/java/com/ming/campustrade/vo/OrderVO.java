package com.ming.campustrade.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class OrderVO {
    
    private Long id;

    private String orderNo;

    private Long productId;

    private String productTitle;

    private BigDecimal productPrice;

    private String productImage;

    private Long buyerId;

    private String buyerNickname;

    private Long sellerId;

    private String sellerNickname;

    private Integer status;

    private LocalDateTime createTime;
}
