package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 活动分类数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于管理员新增/编辑活动分类（学术讲座、体育竞技、文艺演出等）。</p>
 *
 * @author ming
 */
@Data
public class ActivityCategoryDTO {

    /** 分类 ID：新增时为空，编辑时必填（标识要修改哪条分类）。 */
    private Long id;

    /** 分类名称，必填，最长 50 个字符，唯一。 */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称不能超过50个字符")
    private String name;

    /** 排序值，选填，越小越靠前；不传时由数据库使用默认值 0。 */
    @Min(value = 0, message = "排序值不能小于0")
    private Integer sort;
}
