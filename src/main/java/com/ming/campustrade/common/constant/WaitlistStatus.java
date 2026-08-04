package com.ming.campustrade.common.constant;

/**
 * 候补状态常量。
 *
 * <p>候补用户先处于 WAITING 状态；有正式名额释放时，可以被提升为正式预约，
 * 状态变为 PROMOTED。取消或活动结束后，候补记录仍然保留，但 activeMark 置为 null。</p>
 */
public final class WaitlistStatus {

    /** 候补中：当前仍在排队，activeMark 必须为 1。 */
    public static final int WAITING = 0;

    /** 已补位：已经从候补队列转为正式预约，activeMark 必须为 null。 */
    public static final int PROMOTED = 1;

    /** 已取消：用户主动退出候补队列，activeMark 必须为 null。 */
    public static final int CANCELED = 2;

    /** 已失效：活动下架或报名结束，候补资格失效，activeMark 必须为 null。 */
    public static final int EXPIRED = 3;

    /** 工具类不需要创建对象，私有构造方法可以防止误用 new。 */
    private WaitlistStatus() {
    }
}
