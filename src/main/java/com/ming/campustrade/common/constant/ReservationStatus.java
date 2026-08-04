package com.ming.campustrade.common.constant;

/**
 * 预约状态常量。
 *
 * <p>预约记录不使用逻辑删除，而是通过 status 和 activeMark 表示当前状态。
 * 这样用户取消预约后，历史记录仍然保留，方便后续统计和审计。</p>
 */
public final class ReservationStatus {

    /** 已预约：占用活动名额，activeMark 必须为 1。 */
    public static final int CONFIRMED = 0;

    /** 已取消：用户主动取消预约，activeMark 必须为 null。 */
    public static final int CANCELED = 1;

    /** 已失效：活动下架等原因导致预约失效，activeMark 必须为 null。 */
    public static final int EXPIRED = 2;

    /** 工具类不需要创建对象，私有构造方法可以防止误用 new。 */
    private ReservationStatus() {
    }
}
