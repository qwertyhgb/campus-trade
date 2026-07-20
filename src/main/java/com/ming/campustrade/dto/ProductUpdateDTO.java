package com.ming.campustrade.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商品更新数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于接收卖家修改商品信息时提交的数据。与发布接口 {@link ProductPublishDTO} 不同，
 * 本 DTO 的<b>所有字段都是可选的</b>，目的是支持"局部更新"（Partial Update）：
 * 前端只想改哪个字段就传哪个字段，没传的字段保持原值不变。
 * 例如只想改价格时，只需提交 {@code price}，标题、描述等无需重复提交。</p>
 *
 * <p>正因为字段可选，这里没有使用 {@code @NotBlank}/{@code @NotNull} 强制非空，
 * 只对"一旦填写就必须合法"的约束（如长度上限、价格下限）做校验。
 * Service 层在更新时需判断字段是否为 null，仅更新非 null 的字段。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准）。</p>
 *
 * @author ming
 */
@Data
public class ProductUpdateDTO {

    /** 商品标题，选填；若填写则不能超过 100 个字符。 */
    @Size(max = 100, message = "商品标题不能超过100个字符")
    private String title;

    /** 商品描述，选填；若填写则不能超过 1000 个字符。 */
    @Size(max = 1000, message = "商品描述不能超过1000个字符")
    private String description;

    /** 当前售价，选填；若填写则必须大于 0。 */
    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    /** 商品原价，选填；若填写则必须大于 0。 */
    @DecimalMin(value = "0.01", message = "原价必须大于0")
    private BigDecimal originalPrice;

    /** 商品主图 URL，选填。 */
    private String image;

    /** 所属分类 ID，选填。 */
    private Long categoryId;

    /** 成色等级，选填。取值：0=全新，1=几乎全新，2=轻微使用，3=明显使用。 */
    private Integer conditionLevel;
}
