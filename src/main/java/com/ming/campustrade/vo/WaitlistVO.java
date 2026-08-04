package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 候补视图对象（VO，View Object），用于向前端返回候补数据。
 *
 * <p>包含两类信息：</p>
 * <ul>
 *   <li>候补自身信息：id、queuePosition、status、createTime</li>
 *   <li>关联活动信息：activityId、activityTitle、activityLocation、coverImage、活动起止时间
 *       （由 Service 层根据 activityId 批量查询后填充，前端无需再发请求）</li>
 * </ul>
 *
 * <p><b>queuePosition 与实际位置的区别：</b><br>
 * queuePosition 是加入候补时的快照位置（例如第 5 位）。
 * 如果排在前面的用户取消候补，实际位置会提前（变成第 3 位），
 * 但 queuePosition 字段不会变 —— 实际位置需要单独调用
 * {@code getMyWaitlistPosition} 动态计算。</p>
 *
 * @author ming
 */
@Data
public class WaitlistVO {

    /** 候补记录 ID。 */
    private Long id;

    // ==================== 关联活动信息 ====================

    /** 活动 ID。 */
    private Long activityId;

    /** 活动标题。 */
    private String activityTitle;

    /** 活动地点。 */
    private String activityLocation;

    /** 活动封面图 URL。 */
    private String coverImage;

    /** 活动开始时间。 */
    private LocalDateTime activityStartTime;

    /** 活动结束时间。 */
    private LocalDateTime activityEndTime;

    // ==================== 候补自身信息 ====================

    /** 加入时的排队位置（快照值，不随前方人员退出而变化）。 */
    private Integer queuePosition;

    /** 候补状态：0=候补中，1=已补位，2=已取消，3=已失效（见 WaitlistStatus）。 */
    private Integer status;

    /** 加入候补队列的时间。 */
    private LocalDateTime createTime;
}
