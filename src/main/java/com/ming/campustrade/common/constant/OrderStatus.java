package com.ming.campustrade.common.constant;

/**
 * 订单状态常量
 * 定义订单在交易平台中的各种状态
 */
public class OrderStatus {

    /**
     * 待付款
     */
    public static final int PENDING = 0;

    /**
     * 已确认（已付款）
     */
    public static final int CONFIRMED = 1;

    /**
     * 已取消
     */
    public static final int CANCELED = 2;
}
