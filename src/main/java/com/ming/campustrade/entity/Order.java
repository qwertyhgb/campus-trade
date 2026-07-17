package com.ming.campustrade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("`order`")
public class Order {

    private Long id;

    private String orderNo;

    private Long productId;

    private String productTitle;

    private BigDecimal productPrice;

    private String productImage;

    private Long buyerId;

    private Long sellerId;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
