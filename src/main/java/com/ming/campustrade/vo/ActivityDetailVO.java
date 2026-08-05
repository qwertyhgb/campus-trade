package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 活动详情视图对象（VO，View Object），用于活动详情页展示。
 *
 * <p>相比列表项 VO，详情页需要完整信息：详细描述、报名时间段、审核信息等。
 * 同样携带 Service 层填充的 {@code categoryName}、{@code organizerNickname}。</p>
 *
 * @author ming
 */
@Data
public class ActivityDetailVO {

    /** 活动 ID。 */
    private Long id;

    /** 活动标题。 */
    private String title;

    /** 活动详细描述。 */
    private String description;

    /** 活动地点。 */
    private String location;

    /** 封面图片 URL。 */
    private String coverImage;

    /** 所属分类 ID。 */
    private Long categoryId;

    /** 分类名称（关联 activity_category 表填充）。 */
    private String categoryName;

    /** 活动开始时间。 */
    private LocalDateTime startTime;

    /** 活动结束时间。 */
    private LocalDateTime endTime;

    /** 报名开始时间。 */
    private LocalDateTime enrollStartTime;

    /** 报名截止时间。 */
    private LocalDateTime enrollEndTime;

    /** 最大参与人数。 */
    private Integer maxCount;

    /** 当前已预约人数。 */
    private Integer currentCount;

    /** 候补人数；候补模块完成前由活动服务暂时填充为 0。 */
    private Integer waitingListCount;

    /** 活动状态，见 ActivityStatus：0草稿 1待审核 2审核拒绝 3报名中 4报名结束 5进行中 6已结束 7已下架。 */
    private Integer status;

    /** 组织者用户 ID。 */
    private Long organizerId;

    /** 组织者昵称（关联 user 表填充）。 */
    private String organizerNickname;

    /** 审核人用户 ID（管理员）。 */
    private Long reviewerId;

    /** 审核时间。 */
    private LocalDateTime reviewTime;

    /** 拒绝原因（审核拒绝时非空，组织者可见）。 */
    private String rejectReason;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
