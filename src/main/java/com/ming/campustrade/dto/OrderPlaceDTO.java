package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderPlaceDTO {
    
    @NotNull(message = "商品ID不能为空")
    private Long productId;
}
