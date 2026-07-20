package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下单数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于接收买家下单时提交的参数。当前下单只需要指定要购买的商品 ID，
 * 买家身份由登录态（Token）解析得到，价格等信息由后端根据商品 ID 查询确定，
 * 因此前端无需（也不应）传入价格，避免被篡改。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class OrderPlaceDTO {

    /**
     * 要购买的商品 ID，必填。
     *
     * <p>{@link NotNull} 保证不为 null；Service 层还需进一步校验该商品是否存在、是否在售。</p>
     */
    @NotNull(message = "商品ID不能为空")
    private Long productId;
}
