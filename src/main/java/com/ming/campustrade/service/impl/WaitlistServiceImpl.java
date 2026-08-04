package com.ming.campustrade.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.ActivityStatus;
import com.ming.campustrade.common.constant.ReservationStatus;
import com.ming.campustrade.common.constant.WaitlistStatus;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.entity.Activity;
import com.ming.campustrade.entity.Reservation;
import com.ming.campustrade.entity.WaitingList;
import com.ming.campustrade.event.WaitlistJoinedEvent;
import com.ming.campustrade.event.WaitlistPromotedEvent;
import com.ming.campustrade.mapper.ActivityMapper;
import com.ming.campustrade.mapper.ReservationMapper;
import com.ming.campustrade.mapper.WaitingListMapper;
import com.ming.campustrade.messaging.NotificationEventPublisher;
import com.ming.campustrade.service.WaitlistService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;
import com.ming.campustrade.vo.WaitlistVO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 候补服务实现类 —— 处理候补队列的核心业务。
 *
 * <p><b>本类引入的新知识点：悲观锁（SELECT ... FOR UPDATE）。</b><br>
 * 和预约模块的"条件更新（乐观思路）"是两种不同的并发控制方案：</p>
 *
 * <table border="1">
 *   <tr><th>场景</th><th>并发方案</th><th>原因</th></tr>
 *   <tr><td>预约抢名额</td><td>条件更新（乐观）</td>
 *       <td>更新本身是原子的，一条 UPDATE + WHERE 条件搞定</td></tr>
 *   <tr><td>加入候补</td><td>悲观锁 FOR UPDATE</td>
 *       <td>需要"读 MAX(位置) → 算新位置 → INSERT"三步，是读-改-写循环，
 *           条件更新解决不了</td></tr>
 * </table>
 *
 * <p><b>为什么锁活动行而不是候补表行？</b><br>
 * 排队位置的计算依赖"当前队列最大值"，而队列是活动的属性。
 * 锁住活动行后：同一活动的候补加入互斥（串行计算位置），
 * 不同活动的候补加入互不干扰（不同行，锁不冲突）。
 * 副作用：预约（UPDATE 活动行也要拿锁）和候补（FOR UPDATE 拿锁）互相排他，
 * "满员检查"也变可靠了 —— 不会出现一边预约一边候补交错进行的情况。</p>
 *
 * @author ming
 */
@Slf4j
@Service
@SuppressWarnings("null")
public class WaitlistServiceImpl implements WaitlistService {

    /** 活动 Mapper：悲观锁查活动（FOR UPDATE）。 */
    private final ActivityMapper activityMapper;

    /** 候补 Mapper：查重、查最大位置、插入候补记录。 */
    private final WaitingListMapper waitingListMapper;

    /** 预约 Mapper：补位时插入正式预约记录。 */
    private final ReservationMapper reservationMapper;

    /**
     * 通知事件发布器：加入候补/补位成功后，在事务提交后发送 RabbitMQ 事件。
     *
     * <p>只依赖 RabbitTemplate，不会与业务 Service 形成循环依赖。</p>
     */
    private final NotificationEventPublisher notificationEventPublisher;

    public WaitlistServiceImpl(ActivityMapper activityMapper, WaitingListMapper waitingListMapper,
                               ReservationMapper reservationMapper,
                               NotificationEventPublisher notificationEventPublisher) {
        this.activityMapper = activityMapper;
        this.waitingListMapper = waitingListMapper;
        this.reservationMapper = reservationMapper;
        this.notificationEventPublisher = notificationEventPublisher;
    }

    /**
     * 加入候补队列（名额已满时调用）。
     *
     * <p><b>为什么必须加 @Transactional？（新手最易踩的坑）</b><br>
     * 5.2 的 {@code SELECT ... FOR UPDATE} 加的行锁，只有当事务提交或回滚时才会释放。
     * 如果方法上没有 @Transactional，这条查询执行完锁就立即释放了，
     * 后面"读 MAX → 算位置 → 插入"期间其他事务就能进来 —— 等于没锁。
     * 事务把整个方法包起来，锁才能从 5.2 一直持有到方法结束。</p>
     *
     * <p><b>锁的获取时机：</b>@Transactional 开启事务（此刻开始持有数据库连接）→
     * 5.2 执行 FOR UPDATE 查询拿到行锁 → 后续所有读到的数据都是锁内最新值 →
     * 方法正常结束提交事务 / 抛异常回滚事务 → 锁释放。</p>
     *
     * @param activityId 活动 ID
     * @throws BusinessException 未登录 / 活动不存在 / 状态非报名中 / 不在报名时间 / 自约 / 未满员 / 重复候补
     */
    @Override
    @Transactional
    public void joinWaitlist(Long activityId) {
        // ===== 5.1 获取当前登录用户 =====
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        // 参数防御性校验（与预约模块保持一致，防止 null 传入 MyBatis）
        if (activityId == null || activityId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动ID不合法");
        }

        // ===== 5.2 悲观锁查活动（并发核心，重点！） =====
        // 等价 SQL: SELECT * FROM activity WHERE id = ? AND deleted = 0 FOR UPDATE
        //
        // 【运行机制】FOR UPDATE 给这一行加"排他锁"：
        //   - 同一时刻只有一个事务能拿到这把锁，其他事务的 FOR UPDATE / UPDATE 阻塞等待
        //   - 锁在事务提交/回滚时释放（所以本方法必须 @Transactional，见方法注释）
        //
        // 【为什么必须用悲观锁而不是普通查询？】
        // 加入候补 = "读队列最大位置 → 算新位置 → 插入"，三步必须一气呵成。
        // 两个用户同时加入，如果都读到 MAX=5，都会算出 position=6 —— 队列就乱了。
        // 加锁后变成串行：A 插入 6 提交释放锁，B 才读到 MAX=6，插入 7。位置不重复、顺序公平。
        Activity activity = activityMapper.selectByIdForUpdate(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }

        // ===== 5.3 校验活动状态必须是"报名中" =====
        // 草稿/待审核（没审核通过不能候补）、报名结束/已结束（候补无意义）、
        // 已下架（停止所有报名相关操作）
        if (activity.getStatus() == null || activity.getStatus() != ActivityStatus.ENROLLING) {
            throw new BusinessException(ResultCode.WAITLIST_ACTIVITY_NOT_ENROLLING);
        }

        // ===== 5.4 校验当前时间在报名时间段内 =====
        // 与预约模块相同的校验逻辑，复用同一个错误码 RESERVATION_NOT_ENROLL_TIME
        // 防御性判空：数据库 NOT NULL，防止历史脏数据/测试构造不完整实体导致 NPE
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

        // ===== 5.5 校验不能候补自己组织的活动 =====
        // 语义与预约模块一致（组织者不能既当主办方又当参与者），复用错误码
        if (userId.equals(activity.getOrganizerId())) {
            throw new BusinessException(ResultCode.CANNOT_RESERVE_OWN_ACTIVITY);
        }

        // ===== 5.6 校验活动已满员 =====
        // 还有名额就不该来候补，应该直接预约。
        // 注意：这个判断读的是 5.2 锁住的活动数据 —— 锁内数据是最新的，
        // 不可能出现"判断时还有名额，插入时却被别人预约走"的竞态。
        // （预约的 UPDATE 也要拿同一行的锁，被我们的 FOR UPDATE 挡住了）
        Integer currentCount = activity.getCurrentCount();
        Integer maxCount = activity.getMaxCount();
        if (currentCount == null || maxCount == null
                || currentCount < 0 || maxCount < 0 || currentCount > maxCount) {
            // activity 表中这两个字段是 NOT NULL，正常数据不会进入这里。
            // 保留这层防御，可以避免历史脏数据导致空指针，或者生成错误的候补记录。
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动名额数据异常");
        }
        if (currentCount < maxCount) {
            throw new BusinessException(ResultCode.WAITLIST_NOT_FULL);
        }

        // ===== 5.7 校验不能已预约该活动（防止补位卡死，重点！） =====
        // 场景：用户 X 已预约活动 A（活动已满员），又加入候补并成为队首。
        //   有人取消释放名额 → promoteNext 把 X 的候补标记为已补位 →
        //   插入预约记录时被唯一索引 uk_user_activity_active 拦截（X 已有有效预约）
        //   → 事务回滚，X 的候补标记恢复 WAITING → 下次取消再来一遍……
        //   结果：队首永久卡死，X 后面的候补全部饿死，补位机制瘫痪。
        // 所以必须在入队时拦住：已有有效预约的用户不允许加入候补。
        Reservation activeReservation = reservationMapper.selectActiveReservation(userId, activityId);
        if (activeReservation != null) {
            throw new BusinessException(ResultCode.RESERVATION_ALREADY_EXISTS, "已预约该活动，无需候补");
        }

        // ===== 5.8 校验防重复候补（代码层查重） =====
        // 查"该用户对该活动是否已有有效候补"（status=0 AND active_mark=1）
        // 与预约模块同理：这一步只拦串行重复，并发窗口由 5.10 的唯一索引兜底
        Integer activeWaiting = waitingListMapper.countActiveWaiting(userId, activityId);
        if (activeWaiting != null && activeWaiting > 0) {
            throw new BusinessException(ResultCode.WAITLIST_ALREADY_EXISTS);
        }

        // ===== 5.9 生成排队位置 =====
        // getMaxQueuePosition 返回当前队列最大位置；队列为空时 SQL 的 MAX 返回 null，
        // 此时新用户是第一位，position = 0 + 1 = 1
        Integer maxPosition = waitingListMapper.getMaxQueuePosition(activityId);
        int position = (maxPosition == null ? 0 : maxPosition) + 1;

        // ===== 5.10 插入候补记录 =====
        // status=WAITING（候补中）、activeMark=1（有效，配合唯一索引防并发重复）
        // queuePosition=刚算出的位置；createTime/updateTime 由数据库默认值填充
        WaitingList waitingList = new WaitingList();
        waitingList.setUserId(userId);
        waitingList.setActivityId(activityId);
        waitingList.setQueuePosition(position);
        waitingList.setStatus(WaitlistStatus.WAITING);
        waitingList.setActiveMark(1);
        try {
            int insertedRows = waitingListMapper.insert(waitingList);
            if (insertedRows != 1) {
                // 插入 0 行但没抛异常，同样视为失败（防御性处理）
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "候补记录保存失败");
            }
        } catch (DuplicateKeyException e) {
            // 【唯一索引兜底】：5.8 的代码查重存在并发窗口——
            // 两个请求同时通过查重（都没查到），第二个插入时触发
            // uk_user_activity_active (user_id, activity_id, active_mark) 唯一冲突。
            // 事务整体回滚，无需手动补偿。
            log.warn("重复候补被唯一索引拦截：userId={}, activityId={}", userId, activityId);
            throw new BusinessException(ResultCode.WAITLIST_ALREADY_EXISTS);
        }

        // ===== 5.11 事务提交后发送“加入候补”通知事件 =====
        // 与预约模块同理：必须事务提交后才发送，防止事务回滚产生虚假通知。
        // 这里传入加入时的位置快照，用于生成即时通知；
        // 用户后续查询实际位置时仍然通过数据库动态计算。
        WaitlistJoinedEvent event = WaitlistJoinedEvent.create(
                userId, activityId, waitingList.getId(), position);
        publishAfterCommit(() -> {
            try {
                notificationEventPublisher.publishWaitlistJoined(event);
            } catch (Exception e) {
                // 通知是辅助功能，发送失败不能影响已提交的候补主流程
                log.error("加入候补通知发送失败（不影响候补）：eventId={}", event.getEventId(), e);
            }
        });

        // ===== 5.12 日志 =====
        log.info("加入候补成功：userId={}, activityId={}, position={}", userId, activityId, position);
    }

    /**
     * 取消当前用户对某个活动的有效候补。
     *
     * <p>这里故意不加 {@code @Transactional}：整个操作最终只有一条条件 UPDATE，
     * 数据库会保证这条 UPDATE 的原子性。即使两个请求同时点击取消，只有第一个请求
     * 能匹配 {@code status = WAITING AND active_mark = 1}，第二个请求会得到 0 行更新。</p>
     *
     * @param activityId 活动 ID
     */
    @Override
    public void cancelWaitlist(Long activityId) {
        // ===== 1. 获取当前登录用户 =====
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        // Service 可能被其他代码直接调用，不能只依赖 Controller 的参数校验。
        if (activityId == null || activityId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动ID不合法");
        }

        // 先查出候补记录，是为了拿到它的 id；取消时仍然使用条件 UPDATE 做并发兜底。
        WaitingList waitlist = waitingListMapper.selectActiveWaiting(userId, activityId);
        if (waitlist == null) {
            throw new BusinessException(ResultCode.WAITLIST_NOT_FOUND);
        }

        // 等价 SQL：
        // UPDATE waiting_list
        // SET status = 2, active_mark = NULL, process_time = NOW()
        // WHERE id = ? AND status = 0 AND active_mark = 1
        LambdaUpdateWrapper<WaitingList> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(WaitingList::getId, waitlist.getId())
                .eq(WaitingList::getStatus, WaitlistStatus.WAITING)
                .eq(WaitingList::getActiveMark, 1)
                .set(WaitingList::getStatus, WaitlistStatus.CANCELED)
                // activeMark 置为 NULL 后，用户以后可以重新加入同一活动的候补。
                .set(WaitingList::getActiveMark, null)
                .set(WaitingList::getProcessTime, LocalDateTime.now());

        int rows = waitingListMapper.update(null, wrapper);
        if (rows != 1) {
            // 例如两个请求同时取消：其中一个已经先更新成功，另一个就匹配不到条件。
            throw new BusinessException(ResultCode.WAITLIST_STATUS_ERROR);
        }

        log.info("取消候补成功：userId={}, activityId={}, waitlistId={}",
                userId, activityId, waitlist.getId());
    }

    /**
     * 查询当前用户的全部候补历史。
     *
     * <p>不过滤状态，这样前端可以同时展示候补中、已补位、已取消和已失效记录。
     * 活动信息通过一次批量查询填充，避免循环中逐条查询活动造成 N+1 问题。</p>
     *
     * @return 当前用户的候补记录；没有记录时返回空列表
     */
    @Override
    public List<WaitlistVO> getMyWaitlists() {
        // ===== 1. 获取当前登录用户 =====
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        // 不过滤 status：历史记录对用户仍然有展示价值。
        LambdaQueryWrapper<WaitingList> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WaitingList::getUserId, userId)
                .orderByDesc(WaitingList::getCreateTime);
        List<WaitingList> waitlists = waitingListMapper.selectList(wrapper);
        if (waitlists.isEmpty()) {
            return List.of();
        }

        // 收集并去重活动 ID，避免同一活动历史记录重复查询。
        List<Long> activityIds = waitlists.stream()
                .map(WaitingList::getActivityId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        // 一次批量查活动，再转成 Map，组装 VO 时只做内存查找。
        Map<Long, Activity> activityMap = activityIds.isEmpty() ? Map.of()
                : activityMapper.selectByIds(activityIds).stream()
                        .filter(activity -> activity.getId() != null)
                        .collect(Collectors.toMap(Activity::getId, Function.identity(), (first, second) -> first));

        // ===== 4. 组装返回对象 =====
        return waitlists.stream().map(waitlist -> {
            WaitlistVO vo = new WaitlistVO();
            vo.setId(waitlist.getId());
            vo.setActivityId(waitlist.getActivityId());
            vo.setQueuePosition(waitlist.getQueuePosition());
            vo.setStatus(waitlist.getStatus());
            vo.setCreateTime(waitlist.getCreateTime());

            Activity activity = activityMap.get(waitlist.getActivityId());
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

    /**
     * 动态计算当前用户在某个活动候补队列中的实际位置。
     *
     * <p>不直接返回记录里的 queuePosition，因为它只是加入时的位置快照。
     * 前面有人取消后，实际位置会提前；查询时统计仍处于 WAITING 状态且位置更小的记录，
     * 再加 1 即可得到当前实际位置。</p>
     *
     * @param activityId 活动 ID
     * @return 实际排队位置，从 1 开始
     */
    @Override
    public Integer getMyWaitlistPosition(Long activityId) {
        // ===== 1. 获取当前登录用户 =====
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long userId = currentUser.getId();

        if (activityId == null || activityId <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动ID不合法");
        }

        // 只有有效候补记录才能查询排队位置。
        WaitingList waitlist = waitingListMapper.selectActiveWaiting(userId, activityId);
        if (waitlist == null) {
            throw new BusinessException(ResultCode.WAITLIST_NOT_FOUND);
        }
        if (waitlist.getQueuePosition() == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "候补排队位置数据异常");
        }

        Integer beforeCount = waitingListMapper.countBeforePosition(
                activityId, waitlist.getQueuePosition());
        return (beforeCount == null ? 0 : beforeCount) + 1;
    }

    /**
     * 在当前事务成功提交后执行任务（事务提交后发消息的通用方法）。
     *
     * <p>afterCommit 保证：只有事务真正提交成功才发送消息；
     * 事务回滚则任务不执行，避免产生虚假通知。</p>
     *
     * @param task 事务提交后要执行的任务（通常是发送通知事件）
     */
    private void publishAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 防御性兜底：调用方没有开启事务时直接执行
            task.run();
        }
    }

    // ==================== 候补补位 ====================

    /**
     * 把候补队首的成员补位为正式预约（取消预约释放名额后由 ReservationServiceImpl 调用）。
     *
     * <p><b>为什么必须用 REQUIRES_NEW 独立事务？（重点）</b><br>
     * 调用方 {@code cancelReservation} 自身是一个事务（事务A，标记取消 + 释放名额）。
     * 如果补位用默认传播（REQUIRED）加入事务A，补位一旦抛异常，Spring 会把事务A
     * 标记为 rollback-only —— 即使调用方 catch 了异常，事务A提交时仍会抛
     * {@code UnexpectedRollbackException}，整个取消一起回滚，用户连取消都做不了。<br>
     * REQUIRES_NEW 强制开一个全新的独立事务（事务B）：补位失败 → 事务B自己回滚 →
     * 异常被调用方 catch → 事务A正常提交。取消成功，名额保持释放状态
     * （其他用户可以直接预约，或等下次取消再触发补位）。</p>
     *
     * <p><b>为什么补位也要锁活动行？</b><br>
     * 单次补位只需要条件更新，但多个用户同时取消预约时，可能同时触发多个补位事务。
     * 如果它们都只查普通活动记录，两个事务可能同时看到同一个队首：一个事务成功后，
     * 另一个事务竞争失败并直接结束，导致第二个已经释放的名额没有被利用。
     * 现在用 {@code SELECT ... FOR UPDATE} 锁住活动行，把同一活动的补位串行化，
     * 并在一个事务中循环处理所有当前可用名额：</p>
     * <ol>
     *   <li>活动行锁：保证同一活动的加入候补、预约和补位有统一的并发顺序；</li>
     *   <li>条件更新候补记录：即使取消候补与补位并发，也不会把已取消记录补位；</li>
     *   <li>预约唯一索引：防止同一用户生成两条有效预约。</li>
     * </ol>
     *
     * <p><b>失败语义：</b>无队首候补 / 活动不存在或已终结 / 竞争失败都直接 return，
     * 不是错误，不抛异常 —— 补位是"尽力而为"，失败不影响调用方的取消流程。</p>
     *
     * @param activityId 活动 ID
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void promoteNext(Long activityId) {
        // ===== 2.1 参数防御性校验（与其它方法保持一致） =====
        if (activityId == null || activityId <= 0) {
            log.warn("补位参数不合法：activityId={}", activityId);
            return;
        }

        // 收集本次补位事务中所有成功补位的事件，
        // 等事务提交后统一发送（见 2.7）——不在循环里逐个发送
        List<WaitlistPromotedEvent> promotedEvents = new ArrayList<>();

        // ===== 2.2 悲观锁查活动 =====
        // 这里必须使用 FOR UPDATE，而不是普通 selectById：
        //   ① 同一个活动同时发生多次取消时，多个补位事务会串行处理；
        //   ② 加入候补、直接预约、补位都会争抢同一条活动行，数据库会自动排队；
        //   ③ 读取到的 current_count/status 是锁内最新值，不会基于旧快照补位。
        //
        // 这个方法由 cancelReservation 的 afterCommit 回调调用，所以外层取消事务
        // 已经提交并释放活动行锁，此处不会再出现“外层持锁、内层等待”的死锁。
        Activity activity = activityMapper.selectByIdForUpdate(activityId);
        if (activity == null) {
            log.info("补位跳过：活动不存在 activityId={}", activityId);
            return;
        }

        // 候补只在“报名中”状态有意义。报名一结束，定时任务或下架流程会把
        // WAITING 改成 EXPIRED；这里再做一次状态校验，是数据库更新前的最后防线。
        if (activity.getStatus() == null || activity.getStatus() != ActivityStatus.ENROLLING) {
            log.info("补位跳过：活动不在报名中 activityId={}, status={}",
                    activityId, activity.getStatus());
            return;
        }

        Integer currentCount = activity.getCurrentCount();
        Integer maxCount = activity.getMaxCount();
        if (currentCount == null || maxCount == null
                || currentCount < 0 || maxCount < 0 || currentCount > maxCount) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动名额数据异常，无法补位");
        }

        // ===== 2.3 循环补位，直到没有空位或候补队列为空 =====
        // 一个请求可能对应一个释放的名额，但多个取消请求也可能在短时间内
        // 连续释放多个名额。循环可以避免“只补一个人，剩余空位无人处理”的问题。
        while (currentCount < maxCount) {
            WaitingList first = waitingListMapper.selectFirstWaiting(activityId);
            if (first == null) {
                log.info("补位结束：候补队列为空 activityId={}, currentCount={}, maxCount={}",
                        activityId, currentCount, maxCount);
                // 不能直接 return：前面可能已经成功补位过一个或多个用户，
                // 方法末尾还要统一注册 afterCommit 回调发送补位通知。
                // 使用 break 退出循环，确保这些通知不会被跳过。
                break;
            }

            // ===== 2.4 条件更新候补记录（并发安全第一道闸门） =====
            // 即使用户此刻正在取消候补，也只能由仍然满足 WAITING + active_mark=1
            // 的记录进入补位。更新失败时不要抛异常，重新查询队首即可。
            LambdaUpdateWrapper<WaitingList> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(WaitingList::getId, first.getId())
                    .eq(WaitingList::getStatus, WaitlistStatus.WAITING)
                    .eq(WaitingList::getActiveMark, 1)
                    .set(WaitingList::getStatus, WaitlistStatus.PROMOTED)
                    .set(WaitingList::getActiveMark, null)
                    .set(WaitingList::getProcessTime, LocalDateTime.now());
            int rows = waitingListMapper.update(null, updateWrapper);
            if (rows != 1) {
                log.info("队首候补已被其他请求处理，重新查队首：activityId={}, waitlistId={}",
                        activityId, first.getId());
                continue;
            }

            // ===== 2.5 插入正式预约记录（唯一索引第二道闸门） =====
            Reservation reservation = new Reservation();
            reservation.setUserId(first.getUserId());
            reservation.setActivityId(activityId);
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setActiveMark(1);
            try {
                int insertedRows = reservationMapper.insert(reservation);
                if (insertedRows != 1) {
                    throw new BusinessException(ResultCode.INTERNAL_ERROR, "补位预约记录保存失败");
                }
            } catch (DuplicateKeyException e) {
                // 该用户已经有正式预约，通常是用户在补位前主动抢到了空位。
                // 候补记录已经被标记为 PROMOTED，直接跳过当前用户，继续尝试后面的候补。
                // 如果这里抛异常导致事务回滚，队首会恢复 WAITING，可能永久卡住队列。
                log.warn("补位预约遇到已有有效预约，跳过该候补：userId={}, activityId={}",
                        first.getUserId(), activityId);
                continue;
            }

            // ===== 2.6 活动名额 +1（原子更新 + 状态再次校验） =====
            // 这里再次写入 status=ENROLLING：即使管理员刚好在补位期间下架活动，
            // UPDATE 也不会把下架活动的名额改回去。更新失败会让整个补位事务回滚，
            // 候补和预约记录一起恢复，等待下一次补位机会。
            LambdaUpdateWrapper<Activity> activityWrapper = new LambdaUpdateWrapper<>();
            activityWrapper.eq(Activity::getId, activityId)
                    .eq(Activity::getStatus, ActivityStatus.ENROLLING)
                    .lt(Activity::getCurrentCount, maxCount)
                    .setSql("current_count = current_count + 1");
            int updateRows = activityMapper.update(null, activityWrapper);
            if (updateRows != 1) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "补位名额更新失败");
            }

            // 活动行已经被当前事务锁住，数据库不会在这里被其他请求同时改动；
            // 更新成功后同步内存中的计数，下一轮循环才能正确判断是否还有空位。
            currentCount++;
            activity.setCurrentCount(currentCount);
            log.info("候补补位成功：userId={}, activityId={}, 原排队位置={}, 当前名额={}/{}",
                    first.getUserId(), activityId, first.getQueuePosition(), currentCount, maxCount);

            // 收集补位成功事件（先只存内存，不发送）
            promotedEvents.add(WaitlistPromotedEvent.create(
                    first.getUserId(), activityId, first.getId()));
        }

        if (currentCount >= maxCount) {
            log.info("补位结束：活动已满 activityId={}, currentCount={}, maxCount={}",
                    activityId, currentCount, maxCount);
        }

        // ===== 2.7 事务提交后统一发送补位通知 =====
        // 【为什么收集完统一发送，而不是每次补位成功立即发送？】
        // 如果循环里刚插入预约就发消息，消费者可能在补位事务提交前就处理了通知 ——
        // 消费者查数据库时预约记录可能还看不到（事务未提交），存在时序错乱。
        // 收集完所有事件，等本事务（REQUIRES_NEW）提交后统一发送，
        // 保证消费者处理通知时，所有补位数据都已持久化可见。
        if (!promotedEvents.isEmpty()) {
            publishAfterCommit(() -> {
                for (WaitlistPromotedEvent event : promotedEvents) {
                    try {
                        notificationEventPublisher.publishWaitlistPromoted(event);
                    } catch (Exception e) {
                        // 通知是辅助功能，发送失败不能影响已提交的补位主流程
                        log.error("候补补位通知发送失败（不影响补位）：eventId={}", event.getEventId(), e);
                    }
                }
            });
        }
    }
}
