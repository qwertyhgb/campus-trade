package com.ming.campustrade.service;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.vo.ReservationVO;

/**
 * 预约业务逻辑接口（Service 层）。
 *
 * <p><b>为什么不继承 MyBatis-Plus 的 IService？</b><br>
 * 预约逻辑全是跨表操作（activity 表扣名额/reservation 表插记录/释放名额），
 * 没有单表 CRUD 需求，所以不需要 IService 基类，只声明业务方法。</p>
 *
 * <p>当前已实现预约、取消预约、我的预约列表、组织者预约名单。</p>
 *
 * @author ming
 */
public interface ReservationService {

    /**
     * 预约活动（核心并发控制方法）。
     *
     * <p>防重复、防超额、防非法时间、防自约，四重校验 + 数据库条件更新保证并发安全。</p>
     *
     * @param activityId 活动 ID
     */
    void reserve(Long activityId);

    /**
     * 取消预约（释放名额，标记预约为已取消）。
     *
     * <p>用 activityId 而非 reservationId 作为参数，因为用户在页面上点的是"取消预约这个活动"，
     * 前端只知道活动 ID。Service 内部根据 userId + activityId 找到预约记录。</p>
     *
     * <p>取消后名额立即释放，当前阶段不触发候补补位（阶段 5 实现）。</p>
     *
     * @param activityId 活动 ID
     */
    void cancelReservation(Long activityId);

    /**
     * 查询当前用户的全部预约记录（含已取消的历史记录，按预约时间倒序）。
     *
     * <p>返回的 VO 会携带关联的活动信息（标题、地点、时间、封面），
     * 前端展示"我的预约"页时无需再逐个查活动。</p>
     *
     * @return 预约 VO 列表（无预约时返回空列表）
     */
    List<ReservationVO> getMyReservations();

    /**
     * 组织者分页查询某活动的预约名单（按预约时间倒序）。
     *
     * <p>权限：接口层 @PreAuthorize 管"角色"（必须是组织者/管理员），
     * 本方法在 Service 层管"归属"（必须是该活动的组织者）——双重校验。</p>
     *
     * @param activityId 活动 ID
     * @param page       页码（从 1 开始）
     * @param size       每页条数
     * @return 预约 VO 分页对象（含预约用户信息）
     */
    IPage<ReservationVO> getActivityReservations(Long activityId, int page, int size);
}
