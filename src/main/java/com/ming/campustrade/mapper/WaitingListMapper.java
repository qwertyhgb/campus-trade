package com.ming.campustrade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.WaitingList;

/**
 * 候补数据访问层，对应 {@code waiting_list} 表。
 *
 * <p>这里集中放置候补相关的单表查询：查重、查询有效候补、获取最大排队位置、
 * 查询队首候补，以及动态计算指定候补前面的人数。</p>
 */
@Mapper
public interface WaitingListMapper extends BaseMapper<WaitingList> {

    /**
     * 查询某个用户对某个活动是否已有有效候补。
     *
     * <p>返回值使用 Integer，实际结果是 0 或 1：0 表示没有有效候补，
     * 1 表示已经在候补中。只检查 WAITING + active_mark=1，历史记录不算重复。</p>
     *
     * @param userId     用户 ID
     * @param activityId 活动 ID
     * @return 有效候补数量，通常为 0 或 1
     */
    @Select("SELECT COUNT(*) FROM waiting_list "
            + "WHERE user_id = #{userId} "
            + "AND activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1")
    Integer countActiveWaiting(@Param("userId") Long userId,
                               @Param("activityId") Long activityId);

    /**
     * 查询某个活动当前有效候补队列中的最大位置。
     *
     * <p>没有候补记录时，SQL 的 MAX 会返回 null，后续 Service 层应把 null
     * 当作 0，再使用 max + 1 生成第一位候补。</p>
     *
     * @param activityId 活动 ID
     * @return 当前最大有效候补位置；没有记录时返回 null
     */
    @Select("SELECT MAX(queue_position) FROM waiting_list "
            + "WHERE activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1")
    Integer getMaxQueuePosition(@Param("activityId") Long activityId);

    /**
     * 查询某活动候补队列中最靠前的一个有效候补（补位时使用）。
     *
     * <p><b>排序规则（公平性的关键）：</b>ORDER BY queue_position ASC, id ASC。<br>
     * 正常情况下，Service 的活动行锁会让位置按 1、2、3 顺序生成，
     * 数据库中的有效位置唯一索引也会阻止重复位置；
     * 这里仍然加上 id 作为第二排序条件，防止历史数据或人工修复数据出现相同位置时顺序不确定。
     * id 越小代表越早插入，可以保持先进先出（FIFO）。</p>
     *
     * <p>只查 WAITING + active_mark=1 的有效记录，已补位/已取消/已失效的跳过。</p>
     *
     * @param activityId 活动 ID
     * @return 队首候补记录；队列为空返回 null
     */
    @Select("SELECT * FROM waiting_list "
            + "WHERE activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1 "
            + "ORDER BY queue_position ASC, id ASC "
            + "LIMIT 1")
    WaitingList selectFirstWaiting(@Param("activityId") Long activityId);

    /**
     * 查询某个用户对某个活动的有效候补记录（取消候补、查排队位置时使用）。
     *
     * <p>与 {@link #countActiveWaiting} 的区别：count 版只返回数量（0 或 1），
     * 本方法返回完整的候补记录（含 id、queuePosition 等字段），
     * 取消候补时需要用记录的 id 做条件更新，查排队位置时需要用 queuePosition 做统计。</p>
     *
     * @param userId     用户 ID
     * @param activityId 活动 ID
     * @return 有效候补记录；没有有效候补时返回 null
     */
    @Select("SELECT * FROM waiting_list "
            + "WHERE user_id = #{userId} "
            + "AND activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1 "
            + "LIMIT 1")
    WaitingList selectActiveWaiting(@Param("userId") Long userId,
                                   @Param("activityId") Long activityId);

    /**
     * 统计某活动中排在指定位置之前的有效候补人数（动态计算实际排队位置）。
     *
     * <p><b>为什么不直接用 waiting_list 表中存的 queuePosition？</b><br>
     * queuePosition 是加入时的快照。如果排在前面的用户取消候补，
     * 你的实际位置会提前（例如从第 5 变成第 3），但 queuePosition 不会变。
     * 所以实际位置必须动态计算：排在你前面的有效候补人数 + 1。</p>
     *
     * <p><b>索引命中：</b>此查询走 {@code idx_activity_status_position} 联合索引
     * （activity_id + status 等值 + queue_position 范围），性能没问题。</p>
     *
     * <p>等价 SQL：
     * <pre>
     * SELECT COUNT(*) FROM waiting_list
     * WHERE activity_id = ? AND status = 0 AND active_mark = 1
     *   AND queue_position &lt; ?
     * </pre>
     * 实际位置 = 返回值 + 1。</p>
     *
     * @param activityId    活动 ID
     * @param queuePosition 加入时的快照位置
     * @return 排在前面的有效候补人数（实际位置 = 返回值 + 1）
     */
    @Select("SELECT COUNT(*) FROM waiting_list "
            + "WHERE activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1 "
            + "AND queue_position < #{queuePosition}")
    Integer countBeforePosition(@Param("activityId") Long activityId,
                                @Param("queuePosition") Integer queuePosition);

    /**
     * 让指定活动的有效候补失效。
     *
     * <p>活动被管理员下架时，需要立即清理该活动仍处于 WAITING 状态的候补。
     * 这里不删除历史记录，而是把状态改成 EXPIRED，并把 active_mark 置为 NULL：
     * </p>
     *
     * <ul>
     *   <li>保留历史，后续可以统计用户曾经排过哪些活动；</li>
     *   <li>active_mark 置 NULL 后，不会继续占用有效候补的唯一索引；</li>
     *   <li>条件中同时判断 status 和 active_mark，重复执行也不会误改历史记录。</li>
     * </ul>
     *
     * @param activityId 活动 ID
     * @return 实际失效的候补记录数
     */
    @Update("UPDATE waiting_list SET status = 3, active_mark = NULL, process_time = NOW() "
            + "WHERE activity_id = #{activityId} "
            + "AND status = 0 "
            + "AND active_mark = 1")
    int expireActiveWaitingByActivityId(@Param("activityId") Long activityId);

    /**
     * 批量让已经停止报名或已经结束的活动的有效候补失效。
     *
     * <p>这是定时任务的兜底清理。即使管理员下架时没有及时执行单活动清理，
     * 下一分钟定时任务也会根据 activity 表的最终状态把候补改为 EXPIRED。
     * 采用 JOIN 是为了让数据库一次完成“找到活动状态 + 更新候补”，
     * 不需要先查出所有活动 ID 再循环更新，避免 N+1 写操作。</p>
     *
     * <p>状态值说明：4=报名结束、5=进行中、6=已结束、7=已下架。
     * 这些状态都不应再保留有效候补。</p>
     *
     * @return 实际失效的候补记录数
     */
    @Update("UPDATE waiting_list wl "
            + "INNER JOIN activity a ON a.id = wl.activity_id "
            + "SET wl.status = 3, wl.active_mark = NULL, wl.process_time = NOW() "
            + "WHERE wl.status = 0 "
            + "AND wl.active_mark = 1 "
            + "AND (a.deleted = 1 OR a.status IN (4, 5, 6, 7))")
    int expireWaitingForInactiveActivities();
}
