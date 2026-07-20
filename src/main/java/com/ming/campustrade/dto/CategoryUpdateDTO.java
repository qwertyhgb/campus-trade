package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新商品分类数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于管理员后台修改商品分类时接收参数。与新增不同，更新时必须携带分类 ID，
 * 以便后端定位要修改的记录；同时额外开放 {@code status} 字段，用于启用/禁用分类。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class CategoryUpdateDTO {

    /**
     * 分类 ID，必填，用于定位要更新的分类记录。
     *
     * <p>{@link NotNull} 保证不为 null。</p>
     */
    @NotNull(message = "分类ID不能为空")
    private Long id;

    /**
     * 分类名称，必填，最长 50 个字符。
     */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称长度不能超过50个字符")
    private String name;

    /** 分类图标 URL 或图标标识，选填。 */
    private String icon;

    /** 排序权重，选填，数值越大越靠前。 */
    private Integer sort;

    /** 分类状态，选填：1=启用，0=禁用。 */
    private Integer status;
}
