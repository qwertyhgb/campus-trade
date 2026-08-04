package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 预约视图对象（VO，View Object），用于向前端返回预约数据。
 *
 * <p>包含三类信息：</p>
 * <ul>
 *   <li>预约自身信息：reservationId、reservationStatus、createTime</li>
 *   <li>关联活动信息：activityId、activityTitle、activityLocation、coverImage、活动起止时间
 *       （由 Service 层根据 activityId 批量查询后填充，前端无需再发请求）</li>
 *   <li>关联用户信息：userName、userNickname（仅组织者查看预约名单时填充）</li>
 * </ul>
 *
 * @author ming
 */
@Data
public class ReservationVO {

    /** 预约记录 ID。 */
    private Long reservationId;

    // ==================== 关联活动信息 ====================

    /** 活动 ID。 */
    private Long activityId;

    /** 活动标题。 */
    private String activityTitle;

    /** 活动地点。 */
    private String activityLocation;

    /** 活动封面图 URL。 */
    private String coverImage;

    /** 活动开始时间（前端据此判断活动是否已开始，决定能否取消）。 */
    private LocalDateTime activityStartTime;

    /** 活动结束时间。 */
    private LocalDateTime activityEndTime;

    // ==================== 预约自身信息 ====================

    /** 预约状态：0=已预约，1=已取消，2=已失效（见 ReservationStatus）。 */
    private Integer reservationStatus;

    /** 预约创建时间。 */
    private LocalDateTime createTime;

    // ==================== 关联用户信息（组织者名单填充） ====================

    /** 预约用户 ID。 */
    private Long userId;

    /** 预约用户用户名。 */
    private String userName;

    /** 预约用户昵称。 */
    private String userNickname;
}
