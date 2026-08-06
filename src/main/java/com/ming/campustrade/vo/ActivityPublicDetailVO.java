package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 活动公开详情缓存载体，用于 Redis 中所有访问者共用的公开活动详情缓存。
 *
 * <p>审核内部信息不放入此类，从类型层面保证公开缓存不会携带审核人、审核时间和拒绝原因。</p>
 *
 * @author ming
 */
@Data
public class ActivityPublicDetailVO {

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

    /** 分类名称。 */
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

    /** 候补人数。 */
    private Integer waitingListCount;

    /** 活动状态。 */
    private Integer status;

    /** 组织者用户 ID。 */
    private Long organizerId;

    /** 组织者昵称。 */
    private String organizerNickname;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
