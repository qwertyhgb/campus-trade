package com.ming.campustrade.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商品发布数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于接收卖家发布新商品时提交的表单数据。使用 DTO 而非 {@code Product} 实体接收，
 * 是因为发布时 id、sellerId、status、viewCount、createTime 等字段应由后端自动生成或赋值，
 * 不应由前端传入（否则可能被伪造）。DTO 只保留发布所需的字段，并附带校验规则。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class ProductPublishDTO {

    /**
     * 商品标题，必填，最长 100 个字符。
     */
    @NotBlank(message = "商品标题不能为空")
    @Size(max = 100, message = "商品标题不能超过100个字符")
    private String title;

    /**
     * 商品描述，选填，最长 1000 个字符。
     */
    @Size(max = 1000, message = "商品描述不能超过1000个字符")
    private String description;

    /**
     * 当前售价，必填且必须大于 0。
     *
     * <p>{@link NotNull} 保证不为 null；{@link DecimalMin} 限制最小值为 0.01，
     * 避免出现 0 元或负数的非法价格。金额使用 {@link BigDecimal} 保证精度。</p>
     */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    /**
     * 商品原价，选填；若填写则必须大于 0。
     */
    @DecimalMin(value = "0.01", message = "原价必须大于0")
    private BigDecimal originalPrice;

    /**
     * 商品主图 URL，选填（可由前端先上传图片再提交地址）。
     */
    private String image;

    /**
     * 所属分类 ID，选填。
     */
    private Long categoryId;

    /**
     * 成色等级，必填。取值：0=全新，1=几乎全新，2=轻微使用，3=明显使用。
     */
    @NotNull(message = "成色不能为空")
    private Integer conditionLevel;
}
