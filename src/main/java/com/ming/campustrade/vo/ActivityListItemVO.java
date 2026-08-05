package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 活动列表项视图对象（VO，View Object），用于活动列表页展示。
 *
 * <p>相比 {@code Activity} 实体，额外携带 {@code categoryName}（分类名称）、
 * {@code organizerNickname}（组织者昵称）——这两个字段不在活动表中，
 * 由 Service 层关联查询后填充，前端展示时无需再发请求。</p>
 *
 * <p>列表项不返回 description 等大字段，节省带宽（详情页用 ActivityDetailVO）。</p>
 *
 * @author ming
 */
@Data
public class ActivityListItemVO {

    /** 活动 ID。 */
    private Long id;

    /** 活动标题。 */
    private String title;

    /** 封面图片 URL。 */
    private String coverImage;

    /** 所属分类 ID。 */
    private Long categoryId;

    /** 分类名称（关联 activity_category 表填充）。 */
    private String categoryName;

    /** 活动地点。 */
    private String location;

    /** 活动开始时间。 */
    private LocalDateTime startTime;

    /** 活动结束时间。 */
    private LocalDateTime endTime;

    /** 当前已预约人数。 */
    private Integer currentCount;

    /** 最大参与人数。 */
    private Integer maxCount;

    /** 活动状态，见 ActivityStatus：0草稿 1待审核 2审核拒绝 3报名中 4报名结束 5进行中 6已结束 7已下架。 */
    private Integer status;

    /** 组织者用户 ID。 */
    private Long organizerId;

    /** 组织者昵称（关联 user 表填充）。 */
    private String organizerNickname;

    /** 创建时间。 */
    private LocalDateTime createTime;
}
