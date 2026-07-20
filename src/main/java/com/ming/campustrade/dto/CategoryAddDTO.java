package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增商品分类数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于管理员后台新增商品分类时接收参数。使用 DTO 而非 {@code Category} 实体，
 * 是为了限定可提交字段（id、status、createTime 等由后端控制），并附带校验规则。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class CategoryAddDTO {

    /**
     * 分类名称，必填，最长 50 个字符。
     *
     * <p>{@link NotBlank} 拦截 null 与纯空格输入；{@link Size} 限制长度上限。</p>
     */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称长度不能超过50个字符")
    private String name;

    /** 分类图标 URL 或图标标识，选填。 */
    private String icon;

    /** 排序权重，选填，数值越大越靠前。 */
    private Integer sort;
}
