package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Data;

@Data
public class Favorit {
    
    private Long id;

    private Long userId;

    private Long productId;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
