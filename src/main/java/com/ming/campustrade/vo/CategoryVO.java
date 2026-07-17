package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class CategoryVO {
    
    private Long id;
    
    private String name;
    
    private String icon;
    
    private Integer sort;
    
    private Integer status;

    private LocalDateTime createTime;
}
