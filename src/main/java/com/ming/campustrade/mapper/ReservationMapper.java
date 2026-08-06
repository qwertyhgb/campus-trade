package com.ming.campustrade.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.Reservation;

/**
 * 预约数据访问层，对应 {@code reservation} 表。
 *
 * <p>继承 {@link BaseMapper} 后，已经自动拥有常用的增删改查方法；
 * 下面额外增加“查询有效预约”的业务查询，供后续预约 Service 判断重复预约。</p>
 */
@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {

    /**
     * 查询某个用户对某个活动是否已经存在有效预约。
     *
     * <p>不能只按 userId 和 activityId 查询，因为同一用户可能有取消过的历史预约。
     * 这里必须同时限制 status=0 和 active_mark=1，只返回当前仍占用名额的记录。</p>
     *
     * @param userId     用户 ID
     * @param activityId 活动 ID
     * @return 有效预约记录；没有有效预约时返回 null
     */
    @Select("SELECT * FROM reservation "
            + "WHERE user_id = #{userId} "
            + "AND activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1 "
            + "LIMIT 1")
    Reservation selectActiveReservation(@Param("userId") Long userId,
                                        @Param("activityId") Long activityId);

    /**
     * 活动被管理员下架后，批量使仍有效的正式预约失效。
     *
     * <p>这里同时限制 {@code status=0 AND active_mark=1}，因此只会处理仍占用名额的预约；
     * 已取消、已补位后的历史记录不会被重复改写。即使同一操作因重试再次执行，
     * 第二次也只会影响 0 行，这就是批量更新具备幂等性的原因。</p>
     *
     * @param activityId 被下架的活动 ID
     * @return 实际失效的预约数量
     */
    @Update("UPDATE reservation "
            + "SET status = 2, active_mark = NULL, cancel_time = NOW() "
            + "WHERE activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1")
    int expireActiveReservationsByActivityId(@Param("activityId") Long activityId);

    /**
     * 查询某活动所有有效预约的用户列表（活动即将开始通知时使用）。
     *
     * <p>只查 status=CONFIRMED 且 active_mark=1 的记录，
     * 已取消/已失效的预约不占用名额，也不应该收到活动提醒。</p>
     *
     * @param activityId 活动 ID
     * @return 该活动所有有效预约记录
     */
    @Select("SELECT * FROM reservation "
            + "WHERE activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1")
    List<Reservation> selectActiveReservationsByActivityId(@Param("activityId") Long activityId);

    /**
     * 批量查询多个活动的所有有效预约。
     *
     * <p>活动提醒任务可能一次扫描到多个活动，如果循环调用单活动查询，
     * 会产生 N+1 次数据库访问。使用 IN 一次查出全部记录，再由任务按 activityId 分组，
     * 可以明显减少 SQL 次数。</p>
     *
     * @param activityIds 活动 ID 集合（调用方保证不为空）
     * @return 这些活动的所有有效预约记录
     */
    @Select({
            "<script>",
            "SELECT * FROM reservation",
            "WHERE activity_id IN",
            "<foreach collection='activityIds' item='activityId' open='(' separator=',' close=')'>",
            "#{activityId}",
            "</foreach>",
            "AND status = 0",
            "AND active_mark = 1",
            "</script>"
    })
    List<Reservation> selectActiveReservationsByActivityIds(
            @Param("activityIds") List<Long> activityIds);
}
