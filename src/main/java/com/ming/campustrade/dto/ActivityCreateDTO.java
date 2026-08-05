package com.ming.campustrade.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 活动创建数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于接收组织者创建活动时提交的表单数据。使用 DTO 而非 {@code Activity} 实体接收，
 * 是因为创建时 id、status、currentCount、organizerId、reviewerId 等字段应由后端
 * 自动生成或赋值，不应由前端传入（否则可能被伪造）。</p>
 *
 * <p><b>时间先后关系（如 startTime &lt; endTime）属于跨字段校验，DTO 注解无法表达，
 * 在 Service 层创建时统一校验。</b></p>
 *
 * @author ming
 */
@Data
public class ActivityCreateDTO {

    /** 活动标题，必填，最长 100 个字符。 */
    @NotBlank(message = "活动标题不能为空")
    @Size(max = 100, message = "活动标题不能超过100个字符")
    private String title;

    /** 活动详细描述，选填，最长 5000 个字符。 */
    @Size(max = 5000, message = "活动描述不能超过5000个字符")
    private String description;

    /** 活动地点，必填，最长 200 个字符。 */
    @NotBlank(message = "活动地点不能为空")
    @Size(max = 200, message = "活动地点不能超过200个字符")
    private String location;

    /** 封面图片 URL，选填，最长 255 个字符。 */
    @Size(max = 255, message = "封面图片地址不能超过255个字符")
    private String coverImage;

    /** 所属分类 ID，必填。 */
    @NotNull(message = "活动分类不能为空")
    private Long categoryId;

    /** 活动开始时间，必填。 */
    @NotNull(message = "活动开始时间不能为空")
    private LocalDateTime startTime;

    /** 活动结束时间，必填。 */
    @NotNull(message = "活动结束时间不能为空")
    private LocalDateTime endTime;

    /** 报名开始时间，必填。 */
    @NotNull(message = "报名开始时间不能为空")
    private LocalDateTime enrollStartTime;

    /** 报名截止时间，必填。 */
    @NotNull(message = "报名截止时间不能为空")
    private LocalDateTime enrollEndTime;

    /** 最大参与人数，必填，范围 1~100000。 */
    @NotNull(message = "最大参与人数不能为空")
    @Min(value = 1, message = "最大参与人数至少为1")
    @Max(value = 100000, message = "最大参与人数不能超过100000")
    private Integer maxCount;
}
