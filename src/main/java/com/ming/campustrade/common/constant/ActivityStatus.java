package com.ming.campustrade.common.constant;

import java.util.Map;
import java.util.Set;

/**
 * 活动状态常量 + 状态转换白名单。
 *
 * <p>活动有完整的生命周期：草稿 → 待审核 → 报名中 → 报名结束 → 进行中 → 已结束，
 * 期间可能被驳回（回到草稿）或被管理员下架。</p>
 *
 * <p><b>为什么需要状态转换白名单？</b><br>
 * 状态不能随意跳转（如草稿直接变进行中就是非法状态）。所有状态变更必须经过
 * {@link #canTransition(int, int)} 校验，只允许白名单中定义的合法转换，
 * 防止前端或调用方把活动改成任意状态（如把已结束的活动改回报名中）。</p>
 */
public class ActivityStatus {

    /** 草稿：组织者创建活动后的初始状态，仅自己可见，可编辑。 */
    public static final int DRAFT = 0;

    /** 待审核：组织者提交审核，管理员审核通过前不可报名。 */
    public static final int PENDING_REVIEW = 1;

    /** 审核拒绝：管理员审核不通过，组织者可查看拒绝原因（rejectReason），修改后重新提交。 */
    public static final int REJECTED = 2;

    /** 报名中：审核通过，报名时间未截止，用户可预约。 */
    public static final int ENROLLING = 3;

    /** 报名结束：报名时间已截止，等待活动开始。 */
    public static final int ENROLL_ENDED = 4;

    /** 进行中：活动已经开始（活动开始时间到）。 */
    public static final int ONGOING = 5;

    /** 已结束：活动已结束（活动结束时间到），终态。 */
    public static final int FINISHED = 6;

    /** 已下架：管理员下架，不可报名，终态。 */
    public static final int OFF_SHELF = 7;

    /**
     * 状态转换白名单：key = 当前状态，value = 允许转换到的状态集合。
     *
     * <pre>
     * 草稿(0) ──提交审核──▶ 待审核(1) ──审核通过──▶ 报名中(3) ──报名截止──▶ 报名结束(4) ──活动开始──▶ 进行中(5) ──活动结束──▶ 已结束(6)
     *   │                    │                            │                  │
     *   └──────────────┐     └──审核拒绝──▶ 审核拒绝(2)     └──下架──▶ 已下架(7)  └──下架──▶ 已下架(7)
     *                  │                                  ▲
     *                  └──管理员下架────────────────────────┘
     * 审核拒绝(2) ──修改/重新提交──▶ 草稿(0)；所有非终态均可由管理员下架
     * </pre>
     */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            // 草稿 → 提交审核 / 管理员下架
            DRAFT, Set.of(PENDING_REVIEW, OFF_SHELF),
            // 待审核 → 审核通过（报名中）/ 审核拒绝 / 管理员下架
            PENDING_REVIEW, Set.of(ENROLLING, REJECTED, OFF_SHELF),
            // 审核拒绝 → 修改后回到草稿 / 直接重新提交 / 管理员下架
            REJECTED, Set.of(DRAFT, PENDING_REVIEW, OFF_SHELF),
            // 报名中 → 报名截止（定时任务）/ 管理员下架
            ENROLLING, Set.of(ENROLL_ENDED, OFF_SHELF),
            // 报名结束 → 活动开始（定时任务）/ 管理员下架
            ENROLL_ENDED, Set.of(ONGOING, OFF_SHELF),
            // 进行中 → 活动结束（定时任务）/ 管理员下架
            ONGOING, Set.of(FINISHED, OFF_SHELF),
            // 已结束 / 已下架：终态，不允许再转换
            FINISHED, Set.of(),
            OFF_SHELF, Set.of()
    );

    /**
     * 校验状态转换是否合法。
     *
     * <p>所有状态变更（提交审核、管理员审核、下架、定时任务推进）都必须先调用本方法，
     * 非法转换直接拒绝，保证状态机不被绕过。</p>
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true=转换合法，false=非法转换
     */
    public static boolean canTransition(int from, int to) {
        Set<Integer> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * 获取某状态允许转换到的所有状态集合。
     *
     * @param from 当前状态
     * @return 允许转换到的状态集合；未知状态返回空集合
     */
    public static Set<Integer> getAllowedTransitions(int from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }
}
