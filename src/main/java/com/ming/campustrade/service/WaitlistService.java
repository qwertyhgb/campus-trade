package com.ming.campustrade.service;

import java.util.List;

import com.ming.campustrade.vo.WaitlistVO;

/**
 * 候补业务逻辑接口（Service 层）。
 *
 * <p><b>候补的业务场景：</b>活动名额已满时，用户可以选择加入候补队列。
 * 一旦有人取消预约释放名额，队首的候补用户自动补位成为正式预约。</p>
 *
 * <p><b>为什么不继承 MyBatis-Plus 的 IService？</b><br>
 * 候补逻辑全是跨表操作（activity 表 + waiting_list 表），没有单表 CRUD 需求，
 * 与 ReservationService 一样只声明业务方法。</p>
 *
 * @author ming
 */
public interface WaitlistService {

    /**
     * 加入候补队列（名额已满时调用）。
     *
     * <p><b>并发控制核心：悲观锁（SELECT ... FOR UPDATE）</b><br>
     * 加入候补需要"读当前最大排队位置 → 计算新位置 → 插入记录"三步，
     * 是典型的读-改-写循环，条件更新解决不了，必须用悲观锁把同一活动的
     * 候补加入串行化，保证排队位置不重复、顺序公平。</p>
     *
     * @param activityId 活动 ID
     */
    void joinWaitlist(Long activityId);

    /**
     * 取消候补（用户主动退出候补队列）。
     *
     * <p><b>不需要 @Transactional：</b>只修改一张表的一条记录，
     * 单条 UPDATE 本身就是原子操作，不需要事务包裹。</p>
     *
     * <p>取消后 activeMark 置为 NULL，用户以后可以重新加入候补。</p>
     *
     * @param activityId 活动 ID
     * @throws BusinessException 未登录 / 候补记录不存在 / 候补状态不允许取消
     */
    void cancelWaitlist(Long activityId);

    /**
     * 查询当前用户的全部候补记录（含已补位、已取消的历史记录）。
     *
     * <p>与预约模块的 getMyReservations 类似：不过滤状态，展示完整历史，
     * 前端根据 status 字段自行决定展示样式。</p>
     *
     * @return 候补 VO 列表（无候补记录时返回空列表）
     */
    List<WaitlistVO> getMyWaitlists();

    /**
     * 查询当前用户在某活动候补队列中的实际排队位置。
     *
     * <p><b>为什么动态计算而不直接用 queuePosition？</b><br>
     * queuePosition 是加入时的快照。排在前面的用户取消候补后，
     * 实际位置会提前，但 queuePosition 不会变。所以实际位置 = 
     * 排在前面的有效候补人数 + 1，每次查询时动态计算。</p>
     *
     * @param activityId 活动 ID
     * @return 实际排队位置（从 1 开始）；没有有效候补记录时抛异常
     * @throws BusinessException 未登录 / 候补记录不存在
     */
    Integer getMyWaitlistPosition(Long activityId);

    /**
     * 把候补队首的成员补位为正式预约（取消预约释放名额后调用）。
     *
     * <p><b>业务场景：</b>活动满员，用户 A 取消预约释放 1 个名额后，
     * 本方法自动把排队最靠前的候补用户转正为正式预约，名额总数保持不变。</p>
     *
     * <p><b>为什么用独立事务（REQUIRES_NEW）？</b><br>
     * 补位是"尽力而为"的辅助操作。如果它加入取消预约的事务（默认传播），
     * 一旦补位抛异常，Spring 会把取消事务标记为 rollback-only，
     * 即使调用方 catch 了异常，取消事务提交时仍会抛 UnexpectedRollbackException，
     * 导致用户连取消都做不了。REQUIRES_NEW 让补位自成一笔事务：
     * 补位失败只回滚自己，取消预约正常提交，名额保持释放状态。</p>
     *
     * <p><b>如何防重复补位（并发安全）：</b><br>
     * 同一活动先通过活动行锁串行化补位，再用条件更新候补记录
     * （WHERE status=WAITING AND active_mark=1）做第二道保护；
     * 插入预约时再由唯一索引 uk_user_activity_active 兜底，
     * 冲突说明该用户已有有效预约，直接幂等处理。</p>
     *
     * <p><b>失败语义：</b>无队首候补 / 活动不存在或已终结 / 竞争失败，
     * 均直接 return（不是错误），不抛异常、不影响调用方的取消流程。</p>
     *
     * @param activityId 活动 ID
     */
    void promoteNext(Long activityId);
}
