package com.ming.campustrade.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 活动审核完成事件 —— 管理员审核活动（通过或驳回）后触发。
 *
 * <p><b>【发送给谁】</b>活动组织者（userId = 组织者 ID）。<br>
 * <b>对应路由键：</b>{@code activity.reviewed}<br>
 * <b>通知类型：</b>5（审核通过）或 6（审核拒绝），由 {@code passed} 字段决定</p>
 *
 * <p><b>【为什么除了活动 ID 还要带 passed 和 rejectReason？】</b><br>
 * 消费者组装通知内容时需要知道审核结果：</p>
 * <ul>
 *   <li>{@code passed = true} → 通知内容：您的活动已通过审核并上架</li>
 *   <li>{@code passed = false} → 通知内容需包含驳回原因：您的活动审核未通过，原因：活动时间配置不合法</li>
 * </ul>
 * <p>如果事件里不带这些字段，消费者就得再查一次数据库才能拿到审核结果，
 * 增加一次不必要的查询。</p>
 *
 * <p><b>【事件发送时机】</b><br>
 * 在 {@code ActivityServiceImpl.reviewActivity()} 方法中，审核结果写入数据库后发送。
 * 注意：审核操作本身已经在事务中，但事件发送放在事务提交之后（或事务之外），
 * 避免"审核尚未提交、消费者就读到了审核事件"的时序问题。</p>
 *
 * @author ming
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ActivityReviewedEvent extends BaseNotificationEvent {

    /** 事件类型（大写蛇形，用于 eventType 字段，与路由键 activity.reviewed 对应）。 */
    public static final String EVENT_TYPE = "ACTIVITY_REVIEWED";

    /** 被审核的活动 ID。 */
    private Long activityId;

    /** 审核是否通过：true=通过（通知类型 5），false=驳回（通知类型 6）。 */
    private Boolean passed;

    /** 驳回原因（passed=false 时由审核人填写，可 null；passed=true 时为 null）。 */
    private String rejectReason;

    /**
     * 创建一条"活动审核完成"事件。
     *
     * @param userId       接收通知的用户 ID（活动组织者）
     * @param activityId   被审核的活动 ID
     * @param passed       审核是否通过
     * @param rejectReason 驳回原因（通过时传 null）
     * @return 初始化完成的活动审核事件
     */
    public static ActivityReviewedEvent create(Long userId, Long activityId,
                                               Boolean passed, String rejectReason) {
        ActivityReviewedEvent event = new ActivityReviewedEvent();
        event.initEvent(EVENT_TYPE, userId);
        event.setActivityId(activityId);
        event.setPassed(passed);
        event.setRejectReason(rejectReason);
        return event;
    }
}