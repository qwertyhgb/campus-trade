package com.ming.campustrade.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ming.campustrade.entity.Activity;

/**
 * 活动数据访问层（Mapper / DAO），操作 {@code activity} 表。
 *
 * <p>继承 {@link BaseMapper} 即可获得单表常用 CRUD 能力，无需手写 SQL。
 * 复杂的多表查询（如活动列表 + 分类名称）在 Service 层组装或后续补充。</p>
 *
 * @author ming
 */
@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * 悲观锁查询：按主键查活动并加行锁（SELECT ... FOR UPDATE）。
     *
     * <p><b>和普通 selectById 的区别：</b><br>
     * FOR UPDATE 会给查到的这一行加<b>排他锁</b>（写锁）：
     * 锁持有期间，其他事务对同一行的 UPDATE / DELETE / SELECT FOR UPDATE 都会阻塞等待，
     * 直到本事务提交或回滚后锁才释放。普通 SELECT（不加 FOR UPDATE）不受影响，仍可读。</p>
     *
     * <p><b>使用场景：</b>候补模块的"读队列最大值 → 计算新位置 → 插入"三步操作。
     * 如果不加锁，两个用户同时读到 MAX=5，都会插入 position=6，队列就乱了。
     * 加锁后同一活动的候补加入变成串行：A 插入 6 并提交，B 才能读到 MAX=6 插入 7。</p>
     *
     * <p><b>必须配合 @Transactional 使用（关键）：</b><br>
     * FOR UPDATE 的锁在事务提交/回滚时才释放。如果方法没有事务，
     * 查询结束后锁立即释放，等于没锁。调用方必须在事务内调用本方法。</p>
     *
     * @param id 活动主键 ID
     * @return 活动实体；不存在返回 null
     */
    @Select("SELECT * FROM activity WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Activity selectByIdForUpdate(@Param("id") Long id);

    /**
     * 查询本轮“报名中 → 报名结束”的候选活动 ID（只查 id 列）。
     *
     * <p>定时任务在条件更新前调用：选出状态为报名中(3)、且报名截止时间已到
     * （enroll_end_time &lt;= now）的活动 ID。只查 ID 是为了下一步逐个清除
     * 这些活动的 Redis 详情缓存；真正执行状态变化的仍是原来的条件 UPDATE，
     * 本查询只是“候选名单”，与更新相互独立。已排除逻辑删除数据（deleted = 0）。</p>
     *
     * @param now 当前时间快照（与条件更新使用同一个时间基准，保证判断一致）
     * @return 候选活动 ID 列表；无候选时返回空列表
     */
    @Select("SELECT id FROM activity "
            + "WHERE status = 3 AND enroll_end_time <= #{now} AND deleted = 0")
    List<Long> selectIdsToEnrollEnded(@Param("now") LocalDateTime now);

    /**
     * 查询本轮“报名结束 → 进行中”的候选活动 ID（只查 id 列）。
     *
     * <p>定时任务在条件更新前调用：选出状态为报名结束(4)、且活动开始时间已到
     * （start_time &lt;= now）的活动 ID。用途与 {@link #selectIdsToEnrollEnded} 相同：
     * 只作为清缓存候选名单，状态变化仍由条件 UPDATE 决定。已排除逻辑删除数据。</p>
     *
     * @param now 当前时间快照（与条件更新使用同一个时间基准）
     * @return 候选活动 ID 列表；无候选时返回空列表
     */
    @Select("SELECT id FROM activity "
            + "WHERE status = 4 AND start_time <= #{now} AND deleted = 0")
    List<Long> selectIdsToOngoing(@Param("now") LocalDateTime now);

    /**
     * 查询本轮“进行中 → 已结束”的候选活动 ID（只查 id 列）。
     *
     * <p>定时任务在条件更新前调用：选出状态为进行中(5)、且活动结束时间已到
     * （end_time &lt;= now）的活动 ID。用途与 {@link #selectIdsToEnrollEnded} 相同：
     * 只作为清缓存候选名单，状态变化仍由条件 UPDATE 决定。已排除逻辑删除数据。</p>
     *
     * @param now 当前时间快照（与条件更新使用同一个时间基准）
     * @return 候选活动 ID 列表；无候选时返回空列表
     */
    @Select("SELECT id FROM activity "
            + "WHERE status = 5 AND end_time <= #{now} AND deleted = 0")
    List<Long> selectIdsToFinished(@Param("now") LocalDateTime now);
}
