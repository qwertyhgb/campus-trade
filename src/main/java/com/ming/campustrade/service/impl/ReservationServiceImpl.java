package com.ming.campustrade.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.ActivityStatus;
import com.ming.campustrade.common.constant.ReservationStatus;
import com.ming.campustrade.common.constant.WaitlistStatus;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.entity.Activity;
import com.ming.campustrade.entity.Reservation;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.entity.WaitingList;
import com.ming.campustrade.event.ReservationCanceledEvent;
import com.ming.campustrade.event.ReservationCreatedEvent;
import com.ming.campustrade.mapper.ActivityMapper;
import com.ming.campustrade.mapper.ReservationMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.mapper.WaitingListMapper;
import com.ming.campustrade.messaging.NotificationEventPublisher;
import com.ming.campustrade.service.ReservationService;
import com.ming.campustrade.service.WaitlistService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.ReservationVO;
import com.ming.campustrade.vo.UserVO;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 预约服务实现类 —— 处理活动的预约核心业务。
 *
 * <p><b>为什么是整个项目最重要的类？</b><br>
 * 预约是典型的"高并发写"场景：一个活动只剩 1 个名额，100 个人同时点预约，
 * 必须保证只有 1 个人成功、99 个人收到"名额已满"。这就是并发控制（防超卖）。</p>
 *
 * <p><b>防超卖的四道防线：</b></p>
 * <ol>
 *   <li><b>业务校验</b>（4.1~4.5）：状态、时间、自约、重复 —— 快速拒绝明显非法的请求</li>
 *   <li><b>代码层查重</b>（4.6）：查"是否已预约" —— 拦住大多数重复请求（快路径）</li>
 *   <li><b>条件更新抢名额</b>（4.7）：数据库行锁 + WHERE 条件 —— <b>并发安全的真正保证</b></li>
 *   <li><b>唯一索引兜底</b>（4.8）：数据库唯一约束 —— 连并发窗口的最后漏洞也堵死</li>
 * </ol>
 *
 * @author ming
 */
/**
 * Eclipse 的 null 分析会把 MyBatis-Plus 的方法引用（例如 Activity::getId）
 * 与框架接口上的 @NonNull 声明进行严格比较，从而产生大量“方法引用需要 unchecked conversion”
 * 的编辑器提示。这些提示来自框架泛型与 Lombok getter 的类型推断差异，并不代表这里真的会
 * 把 null 传给数据库；Service 中已经对用户、活动、时间和分页参数做了运行时校验。
 */
@Slf4j
@Service
@SuppressWarnings("null")
public class ReservationServiceImpl implements ReservationService {

    /** 活动 Mapper：查活动 + 条件更新扣减名额。 */
    private final ActivityMapper activityMapper;

    /** 预约 Mapper：查重 + 插入预约记录。 */
    private final ReservationMapper reservationMapper;

    /** 用户 Mapper：组织者查看预约名单时批量查询用户信息。 */
    private final UserMapper userMapper;

    /** 候补 Service：取消预约释放名额后，尝试把候补队首补位为正式预约。 */
    private final WaitlistService waitlistService;

    /**
     * 候补 Mapper：用户直接预约成功后，清理他原来可能存在的有效候补。
     *
     * <p>一个用户不能同时拥有“正式预约”和“有效候补”两种身份，
     * 否则候补记录可能长期占着队列位置，后续补位时还会产生重复预约冲突。</p>
     */
    private final WaitingListMapper waitingListMapper;

    /**
     * 通知事件发布器：预约/取消成功后，在事务提交后发送 RabbitMQ 事件。
     *
     * <p>只依赖 RabbitTemplate，不会与业务 Service 形成循环依赖。</p>
     */
    private final NotificationEventPublisher notificationEventPublisher;

    public ReservationServiceImpl(ActivityMapper activityMapper, ReservationMapper reservationMapper,
                                  UserMapper userMapper, WaitlistService waitlistService,
                                  WaitingListMapper waitingListMapper,
                                  NotificationEventPublisher notificationEventPublisher) {
        this.activityMapper = activityMapper;
        this.reservationMapper = reservationMapper;
        this.userMapper = userMapper;
        this.waitlistService = waitlistService;
        this.waitingListMapper = waitingListMapper;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    /**
     * 预约活动（核心并发控制方法）。
     *
     * <p><b>为什么必须加 @Transactional？</b><br>
     * 4.7 的"名额+1"和 4.8 的"插入预约记录"必须同生共死：
     * 如果名额扣了但预约记录插入失败，用户没预约上但名额少了 —— 数据不一致。
     * 事务保证：两步要么都成功，要么都回滚（名额自动还原）。</p>
     *
     * <p><b>四重校验的执行顺序（从快到慢）：</b><br>
     * 先做不查库/轻查库的校验（状态、时间、自约），再做查库查重，
     * 最后才是代价最高的条件更新抢名额。这样大多数非法请求在早期就被拒绝，
     * 不会白白占用行锁。</p>
     *
     * @param activityId 活动 ID
     * @throws BusinessException 未登录 / 活动不存在 / 状态非报名中 / 不在报名时间 / 自约 / 重复预约 / 名额已满
     */
    @Override
    @Transactional
    public void reserve(Long activityId) {
        // ===== 4.1 获取当前登录用户 =====
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        // 路径参数正常由 Controller 传入，但 Service 也可能被定时任务、测试代码或其他
        // Service 直接调用，所以这里仍然做一次防御性校验，避免把 null 传给 MyBatis。
        if (activityId == null || activityId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动ID不合法");
        }

        // ===== 4.2 查活动（不存在直接拒绝） =====
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }

        // ===== 4.3 校验活动状态必须是"报名中" =====
        // 草稿/待审核（没审核通过不能约）、报名结束/已结束（约不了）、已下架（停止报名）
        if (activity.getStatus() == null || activity.getStatus() != ActivityStatus.ENROLLING) {
            throw new BusinessException(ResultCode.RESERVATION_ACTIVITY_NOT_ENROLLING);
        }

        // ===== 4.4 校验当前时间在报名时间段内 =====
        // 两个分支用同一个错误码，但 message 区分"太早"还是"太晚"，提升用户体验
        // 数据库表已经把这两个字段定义为 NOT NULL；这里仍然防御性判空，
        // 防止历史脏数据或单元测试构造不完整实体时出现 NullPointerException。
        if (activity.getEnrollStartTime() == null || activity.getEnrollEndTime() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_TIME_INVALID, "活动报名时间配置不完整");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getEnrollStartTime())) {
            throw new BusinessException(ResultCode.RESERVATION_NOT_ENROLL_TIME, "报名还未开始");
        }
        if (now.isAfter(activity.getEnrollEndTime())) {
            throw new BusinessException(ResultCode.RESERVATION_NOT_ENROLL_TIME, "报名已截止");
        }

        // ===== 4.5 校验不能预约自己组织的活动 =====
        // 防止自约：组织者不能既当主办方又当参与者（例如刷报名人数）
        // 用 userId.equals(...) 而不是 organizerId.equals(...)，即使数据库出现异常 NULL
        // 也不会因为调用 null.equals() 导致空指针。
        if (userId.equals(activity.getOrganizerId())) {
            throw new BusinessException(ResultCode.CANNOT_RESERVE_OWN_ACTIVITY);
        }

        // ===== 4.6 防重复预约（代码层查重，快路径） =====
        // 查"该用户对该活动是否已有有效预约"（status=0 AND active_mark=1）
        // 注意：这一步只能拦住"串行"的重复请求；两个请求同时进来都查不到时，
        //       由 4.8 的唯一索引兜底（见 4.8 注释）
        Reservation exist = reservationMapper.selectActiveReservation(userId, activityId);
        if (exist != null) {
            throw new BusinessException(ResultCode.RESERVATION_ALREADY_EXISTS);
        }

        // ===== 4.7 条件更新抢名额（并发核心！面试重点） =====
        // 等价 SQL:
        //   UPDATE activity SET current_count = current_count + 1
        //   WHERE id = ? AND current_count < max_count AND status = 3 AND deleted = 0
        //
        // 为什么能防超卖？【MySQL 行锁原理】
        //   两个请求同时执行这条 UPDATE，MySQL 会先对这条记录加"行锁"：
        //   - 请求 A 先拿到锁：current_count 从 29 → 30，提交释放锁
        //   - 请求 B 等锁释放后再执行：此时 current_count(30) < max_count(30) 为 false，
        //     条件不满足，影响 0 行 —— 数据库层面保证不会两个都成功！
        //
        // 为什么用 setSql 而不是先查后 set？
        //   setSql("current_count = current_count + 1") 是"读-加-写"在数据库内原子完成，
        //   不存在"读出来 → Java 里 +1 → 写回去"中间被别人改掉的窗口。
        //
        // 注意：activity.getMaxCount() 是 4.2 查出来的旧值，只用于构造 WHERE 条件，
        //       current_count 的变化不会影响 max_count，所以旧值不影响正确性。
        LambdaUpdateWrapper<Activity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Activity::getId, activityId)
                .lt(Activity::getCurrentCount, activity.getMaxCount()) // 还有名额
                .eq(Activity::getStatus, ActivityStatus.ENROLLING)     // 还是报名中
                .setSql("current_count = current_count + 1");          // 原子加 1
        int rows = activityMapper.update(null, updateWrapper);

        // 正常情况下按 id 更新只能影响 1 行；0 行表示名额已满或活动状态刚刚变化。
        // 如果出现其他影响行数，说明数据库/Mapper 配置异常，同样不能继续插入预约。
        if (rows != 1) {
            throw new BusinessException(ResultCode.RESERVATION_ACTIVITY_FULL);
        }

        // ===== 4.7.1 查当前用户是否还在候补队列 =====
        // 这一步放在抢到活动行锁之后：预约、加入候补、自动补位都会围绕同一条
        // activity 行进行并发协调，此时读到的候补状态更接近最终结果，
        // 可以减少“刚补位又直接预约”的竞态。
        WaitingList activeWaitlist = waitingListMapper.selectActiveWaiting(userId, activityId);

        // ===== 4.8 插入预约记录 =====
        // status=CONFIRMED（已预约）、activeMark=1（有效，配合唯一索引防并发重复）
        // createTime/updateTime 不手动设置，由数据库 DEFAULT CURRENT_TIMESTAMP 填充
        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setActivityId(activityId);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setActiveMark(1);
        try {
            int insertedRows = reservationMapper.insert(reservation);
            if (insertedRows != 1) {
                // 插入 0 行但没有抛异常时，也必须让事务回滚前面的名额更新，
                // 防止出现“名额减少了，但预约记录没有保存”的数据不一致。
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "预约记录保存失败");
            }
        } catch (DuplicateKeyException e) {
            // 【唯一索引兜底】：4.6 的代码层查重存在并发窗口——
            // 两个请求同时通过查重（都没查到），又都抢到名额（行锁排队执行），
            // 第二个插入时触发 uk_user_activity_active 唯一索引冲突。
            // 此时名额已被 4.7 扣掉，但事务会整体回滚，名额自动还原，无需手动补偿。
            log.warn("重复预约被唯一索引拦截：userId={}, activityId={}", userId, activityId);
            throw new BusinessException(ResultCode.RESERVATION_ALREADY_EXISTS);
        }

        // ===== 4.9 正式预约成功后，清理原有效候补 =====
        // 用户可能先加入候补，后来活动出现空位并主动点击“预约”。
        // 预约成功后，这条候补已经没有意义，应标记为 CANCELED，而不是保留 WAITING。
        //
        // 仍然使用条件 UPDATE：如果用户在这期间主动取消了候补，rows=0 是正常结果，
        // 因为最终状态已经符合“没有有效候补”的目标，不应该回滚正式预约。
        if (activeWaitlist != null) {
            LambdaUpdateWrapper<WaitingList> waitlistWrapper = new LambdaUpdateWrapper<>();
            waitlistWrapper.eq(WaitingList::getId, activeWaitlist.getId())
                    .eq(WaitingList::getStatus, WaitlistStatus.WAITING)
                    .eq(WaitingList::getActiveMark, 1)
                    .set(WaitingList::getStatus, WaitlistStatus.CANCELED)
                    .set(WaitingList::getActiveMark, null)
                    .set(WaitingList::getProcessTime, LocalDateTime.now());
            int waitlistRows = waitingListMapper.update(null, waitlistWrapper);
            if (waitlistRows == 1) {
                log.info("预约成功后自动取消原候补：userId={}, activityId={}, waitlistId={}",
                        userId, activityId, activeWaitlist.getId());
            } else {
                log.info("预约成功，但原候补已被其他请求处理：userId={}, activityId={}, waitlistId={}",
                        userId, activityId, activeWaitlist.getId());
            }
        }

        // ===== 4.10 事务提交后发送“预约成功”通知事件 =====
        // 【为什么必须事务提交后发送？】
        // 如果在这里直接发送消息：数据库事务后面万一回滚（如 4.9 之后还有异常），
        // 消息已经发出，消费者就会生成一条“用户预约成功”的虚假通知。
        // 用 afterCommit 回调：只有事务真正提交成功才发送，回滚则不发。
        ReservationCreatedEvent event = ReservationCreatedEvent.create(
                userId, activityId, reservation.getId());
        publishAfterCommit(() -> {
            try {
                notificationEventPublisher.publishReservationCreated(event);
            } catch (Exception e) {
                // 通知是辅助功能，发送失败不能影响已提交的预约主流程
                log.error("预约成功通知发送失败（不影响预约）：eventId={}", event.getEventId(), e);
            }
        });

        // ===== 4.11 日志 =====
        log.info("预约成功：userId={}, activityId={}", userId, activityId);
    }

    // ==================== 取消预约 ====================

    /**
     * 取消预约（用户主动取消，释放名额，标记预约为已取消）。
     *
     * <p><b>为什么必须加 @Transactional？</b><br>
     * 2.4 的"标记取消" + 2.5 的"释放名额"必须同生共死：
     * 如果预约标记为已取消但名额没释放，名额就白白少了；
     * 如果名额释放了但预约没取消，下次查"有效预约"还查得到。
     * 事务保证：要么都成功，要么都不做。</p>
     *
     * <p><b>为什么用 activityId 而不是 reservationId？</b><br>
     * 前端用户点的是"取消预约这个活动"，前端只知道活动 ID，
     * Service 内部通过 {@link ReservationMapper#selectActiveReservation} 根据
     * userId + activityId 找到对应的预约记录。</p>
     *
     * @param activityId 活动 ID
     * @throws BusinessException 未登录 / 参数不合法 / 预约不存在 / 活动不存在 / 活动已开始 / 重复取消
     */
    @Override
    @Transactional
    public void cancelReservation(Long activityId) {
        // ===== 2.1 获取当前登录用户 =====
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        // 参数防御性校验（与 reserve 保持一致）
        if (activityId == null || activityId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动ID不合法");
        }

        // ===== 2.2 查有效预约记录 =====
        // 查不到说明：没预约过，或已经取消/失效
        Reservation reservation = reservationMapper.selectActiveReservation(userId, activityId);
        if (reservation == null) {
            throw new BusinessException(ResultCode.RESERVATION_NOT_FOUND);
        }

        // ===== 2.3 查活动，校验活动还没开始 =====
        // 活动一旦开始，名额已被占用（现场座位/物料已准备），不能再取消
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            // 不能因为预约记录存在就忽略活动不存在的异常情况。
            // 如果继续执行，后面可能会成功取消预约，但活动名额根本无法释放，造成数据不一致。
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        if (activity.getStartTime() == null) {
            // 正常数据库结构中 start_time 是 NOT NULL，这里是对历史脏数据的防御性处理。
            throw new BusinessException(ResultCode.ACTIVITY_TIME_INVALID, "活动开始时间配置不完整");
        }
        if (LocalDateTime.now().isAfter(activity.getStartTime())) {
            throw new BusinessException(ResultCode.RESERVATION_STATUS_ERROR, "活动已开始，不能取消预约");
        }

        // ===== 2.4 条件更新预约记录（标记取消） =====
        // 等价 SQL:
        //   UPDATE reservation SET status = 1, active_mark = NULL, cancel_time = NOW()
        //   WHERE id = ? AND status = 0 AND active_mark = 1
        //
        // 【关键】activeMark 必须设为 NULL！因为唯一索引 (user_id, activity_id, active_mark)
        //   只对 active_mark=1 有效。设为 NULL 后，用户才能重新预约同一活动。
        //   如果忘了设 NULL，重新预约时会被唯一索引拦住。
        //
        // WHERE 条件加了 status = CONFIRMED，防止并发下重复取消
        // （两个请求同时取消，第一个成功，第二个因 status 已变而影响 0 行）
        LambdaUpdateWrapper<Reservation> reservationWrapper = new LambdaUpdateWrapper<>();
        reservationWrapper.eq(Reservation::getId, reservation.getId())
                .eq(Reservation::getStatus, ReservationStatus.CONFIRMED)
                .eq(Reservation::getActiveMark, 1)
                .set(Reservation::getStatus, ReservationStatus.CANCELED)
                .set(Reservation::getActiveMark, null)
                .set(Reservation::getCancelTime, LocalDateTime.now());
        int cancelRows = reservationMapper.update(null, reservationWrapper);
        if (cancelRows != 1) {
            // 理论上不会走到这里（2.2 刚查出来是有效预约），
            // 但并发下可能被另一个请求抢先取消，兜底处理
            throw new BusinessException(ResultCode.RESERVATION_STATUS_ERROR, "预约已被取消，请勿重复操作");
        }

        // ===== 2.5 条件更新活动表（释放名额） =====
        // 等价 SQL:
        //   UPDATE activity SET current_count = current_count - 1
        //   WHERE id = ? AND current_count > 0 AND deleted = 0
        //
        // gt(currentCount, 0) 是兜底，防止 current_count 被减成负数
        // （理论上一定能成功，因为预约存在就说明名额被占用了）
        // 与预约时一样用 setSql 原子操作，不用先查后设
        // 同时再次确认 start_time 仍在未来，防止前面的时间检查之后刚好到达活动开始时间。
        LocalDateTime releaseCheckTime = LocalDateTime.now();
        LambdaUpdateWrapper<Activity> activityWrapper = new LambdaUpdateWrapper<>();
        activityWrapper.eq(Activity::getId, activityId)
                .gt(Activity::getStartTime, releaseCheckTime)
                .gt(Activity::getCurrentCount, 0)
                .setSql("current_count = current_count - 1");
        int releaseRows = activityMapper.update(null, activityWrapper);
        if (releaseRows != 1) {
            // 预约记录已经标记取消，但活动名额释放失败时必须抛异常，
            // 让 @Transactional 回滚 2.4 的取消更新，保证两张表始终一致。
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动名额释放失败，请稍后重试");
        }

        // ===== 2.6 事务提交后触发候补补位（阶段 5 核心） =====
        // 业务：名额释放后，候补队首自动转正为正式预约，名额 +1，
        //       "取消 = 名额-1 + 补位 = 名额+1"，两者都成功后名额净变化为 0。
        //
        // 【为什么不能在这里直接调用 REQUIRES_NEW？】
        // 当前方法的事务 A 刚刚更新了 activity.current_count，仍然持有活动行锁。
        // 如果此时直接启动补位事务 B，B 也要更新同一行并等待 A 释放锁；
        // A 又在等待 B 返回，可能形成死锁。REQUIRES_NEW 只能隔离事务，
        // 不能提前释放外层事务 A 已经持有的数据库行锁。
        //
        // 所以这里注册 afterCommit 回调：事务 A 成功提交、锁释放后，
        // 再调用使用 REQUIRES_NEW 的 promoteNext，既避免死锁，又保证取消成功。
        triggerPromoteAfterCommit(activityId);

        // ===== 2.7 事务提交后发送“取消预约”通知事件 =====
        // 【接收人特别注意】不是取消者本人，而是活动组织者（organizerId）！
        // 取消者自己发起的操作不需要通知自己；组织者需要知道名额释放了，
        // 以便关注候补用户的自动补位情况。
        // 与 2.6 相同：必须事务提交后才发送，防止事务回滚产生虚假通知。
        ReservationCanceledEvent cancelEvent = ReservationCanceledEvent.create(
                activity.getOrganizerId(), activityId, reservation.getId());
        publishAfterCommit(() -> {
            try {
                notificationEventPublisher.publishReservationCanceled(cancelEvent);
            } catch (Exception e) {
                // 通知是辅助功能，发送失败不能影响已提交的取消主流程
                log.error("取消预约通知发送失败（不影响取消）：eventId={}", cancelEvent.getEventId(), e);
            }
        });

        // ===== 2.8 日志 =====
        log.info("取消预约成功：userId={}, activityId={}", userId, activityId);
    }

    /**
     * 在当前事务成功提交后触发候补补位。
     *
     * <p>正常情况下本方法运行在 {@link #cancelReservation(Long)} 的事务中，
     * 因此使用 Spring 事务同步回调延迟调用。回调中的 {@code promoteNext}
     * 通过 Service 代理进入 {@code REQUIRES_NEW} 独立事务，此时外层活动行锁已经释放。</p>
     *
     * <p>保留“没有事务时直接调用”的兜底分支，方便未来被非事务代码复用。</p>
     *
     * @param activityId 活动 ID
     */
    private void triggerPromoteAfterCommit(Long activityId) {
        Runnable promoteTask = () -> {
            try {
                waitlistService.promoteNext(activityId);
            } catch (Exception e) {
                // 补位是尽力而为的辅助流程，失败不能影响已经提交的取消结果。
                log.error("候补补位失败（不影响取消）：activityId={}", activityId, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    promoteTask.run();
                }
            });
        } else {
            // 防御性兜底：如果调用方没有开启事务，此时不存在需要等待释放的外层行锁。
            promoteTask.run();
        }
    }

    /**
     * 在当前事务成功提交后执行任务（事务提交后发消息的通用方法）。
     *
     * <p><b>【为什么必须事务提交后执行？】</b><br>
     * 如果数据库事务最后回滚，但消息已经发出，消费者就会生成一条“虚假通知”。
     * afterCommit 保证：只有事务真正提交成功才执行任务；事务回滚则任务不执行。</p>
     *
     * <p><b>【事务同步（TransactionSynchronization）是什么】</b><br>
     * Spring 事务框架提供的“事务生命周期回调”：可以在事务提交前（beforeCommit）、
     * 提交后（afterCommit）、回滚后（afterCompletion）挂接自己的逻辑。
     * 本项目用它把“发消息”推迟到事务提交之后。</p>
     *
     * @param task 事务提交后要执行的任务（通常是发送通知事件）
     */
    private void publishAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 正常情况：方法在 @Transactional 事务中，注册 afterCommit 回调
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 防御性兜底：调用方没有开启事务时（如单元测试直接调用），直接执行
            task.run();
        }
    }

    // ==================== 我的预约列表 ====================

    /**
     * 查询当前用户的全部预约记录（含已取消的历史记录）。
     *
     * <p><b>为什么不过滤状态？</b><br>
     * "我的预约"页面需要展示完整历史（已预约的能取消、已取消的显示灰色），
     * 所以查询全部记录，由前端根据 reservationStatus 自行判断展示样式。</p>
     *
     * <p><b>为什么批量查活动而不是循环查？</b><br>
     * 如果对每条预约单独 selectById 查活动，N 条预约会触发 N 次 SQL（N+1 问题）。
     * 正确做法：收集所有 activityId → 一次 selectByIds 批量查出 → 构建 Map
     * → 内存中按 key 取用。列表页记录多时性能差距非常明显。</p>
     *
     * @return 预约 VO 列表（无预约时返回空列表）
     */
    @Override
    public List<ReservationVO> getMyReservations() {
        // ==================== 第 1 步：获取当前登录用户 ====================
        // UserHolder 内部是 ThreadLocal，TokenAuthenticationFilter 在请求进入时
        // 已经把当前登录用户存进去了，这里直接取。
        // 取不到说明没登录（理论上 Security 已拦截，这里是防御性兜底）。
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        // ==================== 第 2 步：查当前用户的所有预约 ====================
        // LambdaQueryWrapper 是 MyBatis-Plus 的"条件构造器"：
        //   eq(字段, 值)        → 等值条件  WHERE user_id = ?
        //   orderByDesc(字段)   → 倒序排序  ORDER BY create_time DESC
        // 等价 SQL: SELECT * FROM reservation WHERE user_id = ? ORDER BY create_time DESC
        //
        // 注意：这里【不过滤状态】——已预约(0)、已取消(1)、已失效(2) 都查出来，
        // 因为"我的预约"页面要展示完整历史（前端根据 reservationStatus 显示不同样式）。
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreateTime);
        List<Reservation> reservations = reservationMapper.selectList(wrapper);

        // 没有预约直接返回空列表：避免后面空集合走 stream 白白消耗性能
        if (reservations.isEmpty()) {
            return List.of();
        }

        // ==================== 第 3 步：批量查活动（N+1 优化核心） ====================
        // 【背景】预约表里只存了 activityId，前端展示需要活动标题/地点/时间。
        // 【错误做法】对每条预约单独 selectById 查活动 → N 条预约 = N 次 SQL（N+1 问题），
        //   每次 SQL 都要经过"网络 → 数据库解析 → 执行 → 返回"，N 大时性能极差。
        // 【正确做法】收集所有 activityId → 1 次批量查询 → 内存里组装。
        //
        // 下面这行是 Stream 操作链，逐个解释每个环节（数据流：预约列表 → 活动ID列表）：
        //   reservations.stream()          → 把 List 变成"流"（流水线传送带，元素逐个流过）
        //   .map(Reservation::getActivityId) → 映射：每个预约对象 → 取出它的 activityId
        //                                      （等价于 r -> r.getActivityId()，方法引用简写）
        //   .filter(Objects::nonNull)      → 过滤：丢掉 null 的 activityId（防御脏数据）
        //   .distinct()                    → 去重：同一个活动被预约过多次（历史记录），
        //                                      ID 只保留一个，避免 SQL 里 IN 重复值浪费查询
        //   .toList()                      → 把流收集回 List
        List<Long> activityIds = reservations.stream()
                .map(Reservation::getActivityId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // selectByIds(ids)：MyBatis-Plus 3.5.16+ 的批量查询方法（旧版 selectBatchIds 已废弃）。
        // 【运行机制】它会把 List 拼成一条 SQL：
        //   SELECT * FROM activity WHERE id IN (?, ?, ?) AND deleted = 0
        // 一次网络往返查出所有活动 —— 这就是 N+1 → 1 的关键。
        //
        // 【空集合保护】activityIds 为空时不能调用 selectByIds：
        //   生成的 SQL 会是 IN () 空列表，不同数据库/驱动对空 IN 的处理不一致
        //   （有的报语法错误），所以用三元表达式：空 → 空 Map，非空 → 查库。
        //
        // 【Collectors.toMap 的运行机制】
        //   selectByIds 返回 List<Activity>，但我们要按 id 快速查找，
        //   List 只能遍历找（O(n)），Map 能用 key 直接定位（O(1)），所以转成 Map：
        //     key   = Activity::getId     → 把活动的 id 作为 Map 的 key
        //     value = Function.identity() → 把活动对象本身作为 value（"原样返回"）
        //   结果：{1: 活动1, 2: 活动2, 3: 活动3}，之后 activityMap.get(id) 一次命中。
        Map<Long, Activity> activityMap = activityIds.isEmpty() ? Map.of()
                : activityMapper.selectByIds(activityIds).stream()
                        .collect(Collectors.toMap(Activity::getId, Function.identity()));

        // ==================== 第 4 步：组装 VO（纯内存操作，零额外 SQL） ====================
        // 再次用 Stream 遍历预约列表，把每条预约转成 ReservationVO：
        //   .map(r -> { ... }) → 对每条预约执行花括号里的转换逻辑
        //   .toList()          → 收集成 List<ReservationVO> 返回
        return reservations.stream().map(r -> {
            ReservationVO vo = new ReservationVO();
            // 预约自身的信息直接从实体拷贝
            vo.setReservationId(r.getId());
            vo.setActivityId(r.getActivityId());
            vo.setReservationStatus(r.getStatus());
            vo.setCreateTime(r.getCreateTime());

            // 从第 3 步构建的 Map 里按 key 取活动 —— HashMap 的 get 是 O(1)，
            // 100 条预约也只是 100 次内存查找，不会触发任何数据库查询。
            // 活动可能已被逻辑删除（查不到），Map 里没有 → activity 为 null，
            // 此时 VO 只填预约信息、活动字段留空，前端显示"活动已下架"即可。
            Activity activity = activityMap.get(r.getActivityId());
            if (activity != null) {
                vo.setActivityTitle(activity.getTitle());
                vo.setActivityLocation(activity.getLocation());
                vo.setCoverImage(activity.getCoverImage());
                vo.setActivityStartTime(activity.getStartTime());
                vo.setActivityEndTime(activity.getEndTime());
            }
            return vo;
        }).toList();
    }

    // ==================== 组织者查看预约名单 ====================

    /**
     * 组织者分页查询某活动当前有效的预约名单（按预约时间倒序）。
     *
     * <p><b>权限：角色 + 归属双重校验</b><br>
     * 接口层 @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')") 只验证了"角色"，<br>
     * 本方法在 Service 层再验证"归属"——必须是该活动的组织者本人。
     * 否则任何组织者都能看别人的活动名单，构成越权访问。</p>
     *
     * <p><b>批量查用户（同 getMyReservations 的 N+1 优化思路）：</b><br>
     * 收集所有 userId → 一次 selectByIds → Map<Long, User> → 内存填充用户名/昵称。</p>
     *
     * @param activityId 活动 ID
     * @param page       页码（从 1 开始）
     * @param size       每页条数
     * <p><b>为什么只查有效预约？</b><br>
     * 组织者看到的是当前占用名额的名单，而不是完整历史。
     * 已取消/已失效记录仍保留在数据库中，但不应继续出现在名单里。</p>
     *
     * @return 当前有效预约 VO 分页对象（含预约用户信息）
     * @throws BusinessException 活动不存在 / 非组织者
     */
    @Override
    public IPage<ReservationVO> getActivityReservations(Long activityId, int page, int size) {
        // ==================== 第 1 步：获取当前登录用户 ====================
        // UserHolder 内部是 ThreadLocal，TokenAuthenticationFilter 在请求进入时
        // 已经把当前登录用户存进去了，这里直接取。
        // 取不到说明没登录（理论上 Security 已拦截，这里是防御性兜底）。
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
    
        // ==================== 第 2 步：防御性参数校验 ====================
        // Controller 层虽然加了 @Min/@Max 注解（由 @Validated 触发校验），
        // 但 Service 方法可能被其他 Service 直接调用（绕过 Controller），
        // 所以这里重复校验一次 —— 防御性编程，避免非法参数传入 MyBatis。
        if (activityId == null || activityId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动ID不合法");
        }
        if (page < 1 || size < 1 || size > 50) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页参数不合法");
        }
    
        // ==================== 第 3 步：查活动（不存在直接拒绝） ====================
        // selectById 是 MyBatis-Plus 的通用方法，按主键查单条记录。
        // 等价 SQL: SELECT * FROM activity WHERE id = ? AND deleted = 0
        // 注意：MyBatis-Plus 的逻辑删除插件会自动追加 AND deleted = 0，
        // 所以这里查不到被逻辑删除的活动，不会出现"活动已删除但还能看到预约名单"的情况。
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
    
        // ==================== 第 4 步：归属校验（防越权，重点！） ====================
        // 【为什么接口层有 @PreAuthorize 还要 Service 层再校验一次？】
        // 接口层 @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')") 只验证了"角色"——
        // 系统里只要你是 ORGANIZER 角色，就能调用这个接口的 URL。
        // 但组织者 A 可以传 activityId=100（组织者 B 的活动）来查看 B 的预约名单 ——
        // 这就是"越权访问"：我有权调用接口，但我无权看这个特定活动的数据。
        //
        // 所以这里必须再校验：
        //   要么当前用户是活动组织者本人（organizerId == userId）；
        //   要么是管理员（管理员可以看到所有活动的名单，方便运营管理）。
        // 两者都不满足 → 拒绝访问。
        if (!currentUser.getId().equals(activity.getOrganizerId())
                && !isAdmin(currentUser)) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_ORGANIZER);
        }
    
        // ==================== 第 5 步：分页查当前有效预约 ====================
        // Page 是 MyBatis-Plus 的分页对象，传入 page（第几页）和 size（每页几条），
        // 分页插件 PaginationInnerInterceptor 会在执行 SQL 前自动做两件事：
        //   ① 先执行 SELECT COUNT(*) 查总条数（用于计算总页数）
        //   ② 再执行 SELECT * ... LIMIT 偏移量, 每页条数 查当前页数据
        // 一次 selectPage 调用就完成了完整的分页查询。
        //
        // 【为什么只查有效预约（status=CONFIRMED 且 activeMark=1）？】
        // 组织者在活动管理后台看到的名单是"当前占用名额的人"，而不是"历史上预约过的人"。
        // 已取消(1)和已失效(2)的记录虽然保留在数据库里（用于追溯），但应当从名单中排除，
        // 因为那些人已经不占名额了。
        //
        // 等价 SQL:
        //   SELECT COUNT(*) FROM reservation
        //   WHERE activity_id = ? AND status = 0 AND active_mark = 1
        //
        //   SELECT * FROM reservation
        //   WHERE activity_id = ? AND status = 0 AND active_mark = 1
        //   ORDER BY create_time DESC
        //   LIMIT ?, ?
        Page<Reservation> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Reservation::getActivityId, activityId)
                .eq(Reservation::getStatus, ReservationStatus.CONFIRMED)
                .eq(Reservation::getActiveMark, 1)
                .orderByDesc(Reservation::getCreateTime);
        IPage<Reservation> reservationPage = reservationMapper.selectPage(pageParam, wrapper);
    
        // ==================== 第 6 步：批量查用户（N+1 优化） ====================
        // 预约表里只存了 userId，前端展示需要用户名和昵称。
        // 与 getMyReservations 的批量查活动同理：
        //   错误做法：循环对每条预约 selectById 查用户 → N 条 = N 次 SQL
        //   正确做法：收集所有 userId → 一次 selectByIds 批量查出 → 构建 Map
        //
        // 防御性判断：如果当前页没有预约记录，userIds 为空集合，
        // 直接跳过 selectByIds（空 IN () 在不同数据库/驱动下兼容性不同）。
        List<Long> userIds = reservationPage.getRecords().stream()
                .map(Reservation::getUserId)
                .distinct()
                .toList();
        Map<Long, User> userMap = userIds.isEmpty() ? Map.of()
                : userMapper.selectByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
    
        // ==================== 第 7 步：组装 VO（纯内存操作） ====================
        // 遍历当前页的预约记录，逐条填充成 ReservationVO。
        // 活动信息直接从第 3 步查到的 activity 对象取（因为都是同一活动的预约，
        // 活动信息共享，不需要从 Map 查）。
        // 用户信息从第 6 步构建的 userMap 取（内存查找，O(1)）。
        List<ReservationVO> voList = reservationPage.getRecords().stream().map(r -> {
            ReservationVO vo = new ReservationVO();
            // 预约自身信息
            vo.setReservationId(r.getId());
            vo.setActivityId(r.getActivityId());
            vo.setReservationStatus(r.getStatus());
            vo.setCreateTime(r.getCreateTime());
    
            // 活动信息：当前页的所有预约都是同一个活动，直接从 activity 对象取，
            // 不用像 getMyReservations 那样从 Map 取。
            vo.setActivityTitle(activity.getTitle());
            vo.setActivityLocation(activity.getLocation());
            vo.setCoverImage(activity.getCoverImage());
            vo.setActivityStartTime(activity.getStartTime());
            vo.setActivityEndTime(activity.getEndTime());
    
            // 用户信息：从第 6 步构建的 userMap 取
            // 用户可能已被删除（理论上预约存在则用户一定存在，但防御性判空总没错）
            vo.setUserId(r.getUserId());
            User user = userMap.get(r.getUserId());
            if (user != null) {
                vo.setUserName(user.getUsername());
                vo.setUserNickname(user.getNickname());
            }
            return vo;
        }).toList();
    
        // ==================== 第 8 步：构建新的分页对象返回 ====================
        // IPage<Reservation> 和 IPage<ReservationVO> 是不同类型，不能直接替换记录。
        // 所以新建一个 IPage<ReservationVO>，把分页信息（页码、每页条数、总条数）
        // 和转换后的记录列表 set 进去，返回给前端。
        // 前端拿到后就知道：当前是第几页、总共多少条、总共多少页。
        IPage<ReservationVO> resultPage = new Page<>(reservationPage.getCurrent(),
                reservationPage.getSize(), reservationPage.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    /**
     * 判断当前用户是否为管理员（双来源，与 ActivityServiceImpl 保持一致）。
     *
     * <p>为什么判断两次？UserVO.getRole() 是 Redis 旧字段（兼容历史 Token），
     * SecurityContext 的 ROLE_ADMIN 是新 RBAC 权限。两者任一命中都算管理员。</p>
     *
     * @param currentUser 当前登录用户
     * @return true=管理员
     */
    private boolean isAdmin(UserVO currentUser) {
        if (currentUser.getRole() != null && currentUser.getRole() >= 1) {
            return true;
        }
        return org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication() != null
                && org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
