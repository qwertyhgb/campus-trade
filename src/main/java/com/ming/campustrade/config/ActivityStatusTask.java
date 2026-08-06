package com.ming.campustrade.config;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ming.campustrade.common.constant.ActivityStatus;
import com.ming.campustrade.entity.Activity;
import com.ming.campustrade.mapper.ActivityMapper;
import com.ming.campustrade.mapper.WaitingListMapper;
import com.ming.campustrade.service.ActivityCacheService;
import com.ming.campustrade.service.OperationLogService;

import lombok.extern.slf4j.Slf4j;

/**
 * 活动状态自动流转定时任务 —— 按时间阈值自动推进活动生命周期。
 *
 * <p><b>业务背景：</b><br>
 * 活动的状态推进依赖时间：报名截止了 → 报名结束；活动开始了 → 进行中；活动结束了 → 已结束。
 * 这些状态变化不需要用户操作，由定时任务每分钟扫描一次自动完成。</p>
 *
 * <p><b>执行频率：</b>每 60 秒执行一次（fixedRate = 60000 毫秒）</p>
 *
 * <p><b>三段自动转换：</b></p>
 * <pre>
 * 报名中(3) ──报名截止时间到──▶ 报名结束(4) ──活动开始时间到──▶ 进行中(5) ──活动结束时间到──▶ 已结束(6)
 * </pre>
 *
 * <p><b>为什么直接用 Mapper 而不是走 Service？</b><br>
 * 这是批量条件更新，一次 UPDATE 处理一批活动，不涉及单条业务的复杂校验
 * （状态机白名单校验在单条操作的 Service 方法里做；这里 WHERE 条件本身就保证了
 * 只会更新状态正确的记录，等价于"隐式白名单"）。直接调 Mapper 更简洁高效。</p>
 *
 * <p><b>为什么用条件 UPDATE 而不是先查再改？</b><br>
 * 防止并发覆盖：如果先 SELECT 出所有"报名中"的活动，再逐个 UPDATE，
 * 中间管理员可能刚好下架了某个活动（状态 → 已下架），此时逐个 UPDATE 会把
 * 已下架的活动改回"报名结束"——状态被覆盖了。
 * 条件 UPDATE 的 WHERE status = 3 保证：只有此刻状态还是"报名中"的记录才会被改，
 * 已被下架的记录不满足条件，不会被误伤。这与订单超时取消是同一个思路。</p>
 *
 * <p><b>为什么不需要 @Transactional？</b><br>
 * 三段更新互相独立，每段本身就是一条原子 UPDATE，不需要放同一个事务。
 * 而且 @Scheduled 方法加 @Transactional 需要跨 Bean 调用代理才生效，
 * 这里没有这个必要（一段失败不影响其他两段）。</p>
 *
 * @author ming
 */
@Component
@Slf4j
@SuppressWarnings("null") // 抑制 MyBatis-Plus Lambda 方法引用（Activity::getStatus 等）与 Eclipse 空类型分析冲突的误报警告
public class ActivityStatusTask {

    /**
     * 活动 Mapper：直接执行批量条件更新。
     * 注入 Mapper 而非 Service —— 批量状态推进不需要 Service 层的单条业务校验。
     */
    private final ActivityMapper activityMapper;

    /**
     * 候补 Mapper：活动生命周期结束后，批量把仍在排队的候补标记为已失效。
     *
     * <p>候补记录没有 deleted 字段，不能用逻辑删除；必须通过
     * status=EXPIRED + active_mark=NULL 保留历史并释放“有效候补”资格。</p>
     */
    private final WaitingListMapper waitingListMapper;

    /**
     * 活动详情缓存组件：定时任务推进活动状态后，清除这些活动的旧详情缓存。
     *
     * <p>定时任务直接改库（不走 Service），状态变化后必须主动删缓存，
     * 否则 Redis 里会一直留着“报名中”等旧状态的详情。只依赖 evict 删 Key，
     * 不改变任何数据库业务；Redis 异常已由 evict 内部吞掉，不影响定时任务。</p>
     */
    private final ActivityCacheService activityCacheService;

    /**
     * 操作审计服务：为无人点击触发的定时状态变化留下“系统操作”记录。
     * 单次批量流转只写一条汇总日志，既能追溯又不会把审计表刷成海量明细。
     */
    private final OperationLogService operationLogService;

    public ActivityStatusTask(ActivityMapper activityMapper,
                              WaitingListMapper waitingListMapper,
                              ActivityCacheService activityCacheService,
                              OperationLogService operationLogService) {
        this.activityMapper = activityMapper;
        this.waitingListMapper = waitingListMapper;
        this.activityCacheService = activityCacheService;
        this.operationLogService = operationLogService;
    }

    /**
     * 每分钟执行一次：扫描到达时间阈值的活动并自动转换状态。
     *
     * <p><b>执行顺序（三段互不依赖，先后执行）：</b></p>
     * <ol>
     *   <li>报名截止 → 报名结束（enroll_end_time 到了）</li>
     *   <li>活动开始 → 进行中（start_time 到了）</li>
     *   <li>活动结束 → 已结束（end_time 到了）</li>
     * </ol>
     *
     * <p>MyBatis-Plus 逻辑删除会自动为 UPDATE 追加 WHERE deleted = 0，
     * 已删除的活动不会被误改。</p>
     */
    @Scheduled(fixedRate = 60000)
    public void autoTransitActivityStatus() {
        // 三段更新共用同一个时间快照，保证本轮任务的判断基准一致。
        LocalDateTime now = LocalDateTime.now();

        // ===== 3.1 报名截止 → 报名结束 =====
        // 即使下面的 WHERE 条件已经限制了 from 状态，也要显式调用状态机白名单。
        // 这样“所有状态变化都必须经过 canTransition”这条规则在代码层面是清晰可见的；
        // 将来有人修改状态常量或白名单时，定时任务也不会悄悄执行非法转换。
        if (!ensureTransitionAllowed(ActivityStatus.ENROLLING, ActivityStatus.ENROLL_ENDED)) {
            return;
        }
        // 条件：还在报名中(3) 且报名截止时间已到
        // 等价 SQL: UPDATE activity SET status = 4
        //           WHERE status = 3 AND enroll_end_time <= NOW() AND deleted = 0
        // 先查出本轮可能被转换的活动 ID（仅用于确定要删哪些 Redis Key，见 evictActivityDetailCaches 说明）
        List<Long> enrollEndedIds = activityMapper.selectIdsToEnrollEnded(now);
        LambdaUpdateWrapper<Activity> enrollEndedWrapper = new LambdaUpdateWrapper<>();
        enrollEndedWrapper.eq(Activity::getStatus, ActivityStatus.ENROLLING)
                .eq(Activity::getDeleted, 0)
                .le(Activity::getEnrollEndTime, now)
                .set(Activity::getStatus, ActivityStatus.ENROLL_ENDED);
        // update(null, wrapper)：null 表示不按实体更新字段，只按 wrapper 的 set 更新
        // 返回受影响行数：>0 说明有活动被转换
        int enrollEndedRows = activityMapper.update(null, enrollEndedWrapper);
        if (enrollEndedRows > 0) {
            log.info("活动状态自动转换：报名中 → 报名结束，影响 {} 条", enrollEndedRows);
            operationLogService.recordSystem("ACTIVITY_AUTO_ENROLL_ENDED", "activity", null,
                    "定时任务自动将活动从报名中流转为报名结束，影响 " + enrollEndedRows + " 条",
                    true, null);
            // 状态已变化，逐个清除这些活动的详情缓存，避免用户看到旧状态
            evictActivityDetailCaches(enrollEndedIds);
        }

        // ===== 3.2 活动开始 → 进行中 =====
        if (!ensureTransitionAllowed(ActivityStatus.ENROLL_ENDED, ActivityStatus.ONGOING)) {
            return;
        }
        // 条件：报名结束(4) 且活动开始时间已到
        // 等价 SQL: UPDATE activity SET status = 5
        //           WHERE status = 4 AND start_time <= NOW() AND deleted = 0
        // 先查出本轮可能被转换的活动 ID（仅用于确定要删哪些 Redis Key，见 evictActivityDetailCaches 说明）
        List<Long> ongoingIds = activityMapper.selectIdsToOngoing(now);
        LambdaUpdateWrapper<Activity> ongoingWrapper = new LambdaUpdateWrapper<>();
        ongoingWrapper.eq(Activity::getStatus, ActivityStatus.ENROLL_ENDED)
                .eq(Activity::getDeleted, 0)
                .le(Activity::getStartTime, now)
                .set(Activity::getStatus, ActivityStatus.ONGOING);
        int ongoingRows = activityMapper.update(null, ongoingWrapper);
        if (ongoingRows > 0) {
            log.info("活动状态自动转换：报名结束 → 进行中，影响 {} 条", ongoingRows);
            operationLogService.recordSystem("ACTIVITY_AUTO_ONGOING", "activity", null,
                    "定时任务自动将活动从报名结束流转为进行中，影响 " + ongoingRows + " 条",
                    true, null);
            // 状态已变化，逐个清除这些活动的详情缓存，避免用户看到旧状态
            evictActivityDetailCaches(ongoingIds);
        }

        // ===== 3.3 活动结束 → 已结束 =====
        if (!ensureTransitionAllowed(ActivityStatus.ONGOING, ActivityStatus.FINISHED)) {
            return;
        }
        // 条件：进行中(5) 且活动结束时间已到
        // 等价 SQL: UPDATE activity SET status = 6
        //           WHERE status = 5 AND end_time <= NOW() AND deleted = 0
        // 先查出本轮可能被转换的活动 ID（仅用于确定要删哪些 Redis Key，见 evictActivityDetailCaches 说明）
        List<Long> finishedIds = activityMapper.selectIdsToFinished(now);
        LambdaUpdateWrapper<Activity> finishedWrapper = new LambdaUpdateWrapper<>();
        finishedWrapper.eq(Activity::getStatus, ActivityStatus.ONGOING)
                .eq(Activity::getDeleted, 0)
                .le(Activity::getEndTime, now)
                .set(Activity::getStatus, ActivityStatus.FINISHED);
        int finishedRows = activityMapper.update(null, finishedWrapper);
        if (finishedRows > 0) {
            log.info("活动状态自动转换：进行中 → 已结束，影响 {} 条", finishedRows);
            operationLogService.recordSystem("ACTIVITY_AUTO_FINISHED", "activity", null,
                    "定时任务自动将活动从进行中流转为已结束，影响 " + finishedRows + " 条",
                    true, null);
            // 状态已变化，逐个清除这些活动的详情缓存，避免用户看到旧状态
            evictActivityDetailCaches(finishedIds);
        }

        // ===== 3.4 清理已经没有资格继续排队的候补 =====
        // 前面的三段 UPDATE 只负责修改 activity.status，不能自动修改 waiting_list。
        // 如果不补这一步，活动报名结束后候补仍会显示为 WAITING，用户还能查到一个
        // 实际已经没有意义的排队位置，promoteNext 也可能误把它补成正式预约。
        //
        // 这里使用批量 JOIN UPDATE：一次把报名结束、进行中、已结束、已下架活动的
        // 有效候补改为 EXPIRED。条件中保留 status=0 AND active_mark=1，
        // 因此定时任务每分钟重复执行也是幂等的，不会改动历史记录。
        try {
            int expiredRows = waitingListMapper.expireWaitingForInactiveActivities();
            if (expiredRows > 0) {
                log.info("候补自动失效：影响 {} 条", expiredRows);
                operationLogService.recordSystem("WAITLIST_AUTO_EXPIRE", "waitlist", null,
                        "定时任务自动使无效活动的候补失效，影响 " + expiredRows + " 条",
                        true, null);
            }
        } catch (Exception e) {
            // 候补清理失败不能阻止下一轮活动状态扫描；下一分钟会再次尝试。
            // 记录错误日志是为了方便排查数据库连接或 SQL 配置问题。
            log.error("候补自动失效失败（下一轮将重试）", e);
        }
    }

    /**
     * 检查定时任务要执行的状态转换是否在统一白名单中。
     *
     * <p>正常情况下这里永远返回 true；单独抽成方法是为了让状态机成为唯一规则来源。
     * 如果未来有人误删或改错 TRANSITIONS 配置，任务会记录错误并停止本轮处理，
     * 不会在数据库中写入一个未被允许的状态。</p>
     */
    private boolean ensureTransitionAllowed(int from, int to) {
        if (ActivityStatus.canTransition(from, to)) {
            return true;
        }
        log.error("活动定时任务发现非法状态转换配置：{} → {}，本轮任务停止", from, to);
        return false;
    }

    /**
     * 批量清除多个活动的详情缓存（仅在本轮确有活动被转换时调用）。
     *
     * <p><b>学习点 1：先查 ID 仅用于确定要删哪些 Redis Key，真正决定状态变化的仍是原来的条件更新。</b><br>
     * 定时任务的状态推进以条件 UPDATE 为准（WHERE 里限定了原状态 + 时间到才更新），
     * 前面的 SELECT id 只是提前记下“可能被转换”的候选名单，两者是相互独立的两件事：
     * 查 ID 决定删哪些缓存，条件更新决定数据库改哪些行。</p>
     *
     * <p><b>学习点 2：查询和更新之间即使管理员下架了活动，条件更新会跳过它；多清一个缓存 Key 没有副作用。</b><br>
     * 候选名单查到的是“报名中”，但 UPDATE 执行前管理员可能已把活动下架（状态变成 7），
     * 此时条件更新会跳过它（WHERE status = 3 不满足）。但我们仍会多删这个 Key ——
     * 这没有副作用：缓存本来就没有就删不掉；有旧缓存则删掉后下次访问回查 MySQL 重新组装，
     * 反而保证不会展示旧数据。</p>
     *
     * <p><b>为什么不用 KEYS activity:detail:* 清缓存？</b><br>
     * KEYS 会遍历整个 Redis 的所有 Key，数据量大时可能阻塞 Redis 服务（单线程被占满）。
     * 这里只按明确的单个 Key（activity:detail:{id}）逐个删除，安全且高效。</p>
     *
     * @param activityIds 候选活动 ID 列表（可能包含查询后被跳过的活动，多清无害）
     */
    private void evictActivityDetailCaches(List<Long> activityIds) {
        for (Long activityId : activityIds) {
            activityCacheService.evict(activityId);
        }
    }
}
