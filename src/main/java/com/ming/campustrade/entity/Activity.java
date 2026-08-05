package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Data;

/**
 * 活动实体类（Entity），与数据库 {@code activity} 表一一映射。
 *
 * <p>活动是预约系统的核心业务对象：组织者创建活动（含时间、地点、人数上限），
 * 管理员审核通过后进入报名阶段，用户可以预约或候补。</p>
 *
 * <p>状态字段 {@code status} 的取值见 {@link com.ming.campustrade.common.constant.ActivityStatus}，
 * 状态变更必须通过白名单校验（不能随意跳转）。</p>
 *
 * @author ming
 */
@Data
public class Activity {

    /** 活动主键 ID。 */
    private Long id;

    /** 活动标题。 */
    private String title;

    /** 活动详细描述（Markdown/纯文本）。 */
    private String description;

    /** 活动地点。 */
    private String location;

    /** 封面图片 URL。 */
    private String coverImage;

    /** 所属分类 ID，关联 activity_category 表。 */
    private Long categoryId;

    /** 活动开始时间。 */
    private LocalDateTime startTime;

    /** 活动结束时间。 */
    private LocalDateTime endTime;

    /** 报名开始时间（在此之前不可预约）。 */
    private LocalDateTime enrollStartTime;

    /** 报名截止时间（在此之后不可预约）。 */
    private LocalDateTime enrollEndTime;

    /** 最大参与人数（预约名额上限）。 */
    private Integer maxCount;

    /**
     * 当前已预约人数。
     *
     * <p>并发控制的关键字段：预约时通过条件更新
     * {@code UPDATE activity SET current_count = current_count + 1
     * WHERE id = ? AND current_count < max_count} 防止超额预约。</p>
     */
    private Integer currentCount;

    /**
     * 活动状态，见 {@link com.ming.campustrade.common.constant.ActivityStatus}：
     * 0草稿 1待审核 2审核拒绝 3报名中 4报名结束 5进行中 6已结束 7已下架。
     */
    private Integer status;

    /** 组织者用户 ID，关联 user 表。 */
    private Long organizerId;

    /** 审核人用户 ID（审核通过/拒绝时记录），关联 user 表。 */
    private Long reviewerId;

    /** 审核时间。 */
    private LocalDateTime reviewTime;

    /** 拒绝原因（审核拒绝时填写，组织者可见）。 */
    private String rejectReason;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志位：0=未删除，1=已删除。
     *
     * <p>{@link TableLogic} 注解让 MyBatis-Plus 自动在查询时追加
     * {@code WHERE deleted = 0}，删除时执行软删除而非物理删除。</p>
     */
    @TableLogic
    private Integer deleted;
}
