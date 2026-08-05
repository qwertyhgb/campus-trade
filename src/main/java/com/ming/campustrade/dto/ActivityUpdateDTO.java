package com.ming.campustrade.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 活动编辑数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于组织者修改活动时提交的数据。<b>所有业务字段均为可选（部分更新）</b>：
 * 前端只传需要修改的字段，未传的保持原值，与商品模块的 updateProfile 思路一致。</p>
 *
 * <p>注意：只有「草稿」和「审核拒绝」状态的活动允许编辑，
 * 状态校验在 Service 层完成（见 ActivityStatus 状态机）。</p>
 *
 * @author ming
 */
@Data
public class ActivityUpdateDTO {

    /** 活动 ID，必填（标识要修改哪条记录）。 */
    @NotNull(message = "活动ID不能为空")
    private Long id;

    /** 活动标题，选填（修改时传入）。 */
    @Size(max = 100, message = "活动标题不能超过100个字符")
    private String title;

    /** 活动详细描述，选填。 */
    @Size(max = 5000, message = "活动描述不能超过5000个字符")
    private String description;

    /** 活动地点，选填。 */
    @Size(max = 200, message = "活动地点不能超过200个字符")
    private String location;

    /** 封面图片 URL，选填，最长 255 个字符。 */
    @Size(max = 255, message = "封面图片地址不能超过255个字符")
    private String coverImage;

    /** 所属分类 ID，选填。 */
    private Long categoryId;

    /** 活动开始时间，选填。 */
    private LocalDateTime startTime;

    /** 活动结束时间，选填。 */
    private LocalDateTime endTime;

    /** 报名开始时间，选填。 */
    private LocalDateTime enrollStartTime;

    /** 报名截止时间，选填。 */
    private LocalDateTime enrollEndTime;

    /** 最大参与人数，选填，范围 1~100000。 */
    @Min(value = 1, message = "最大参与人数至少为1")
    @Max(value = 100000, message = "最大参与人数不能超过100000")
    private Integer maxCount;
}
