package com.ming.campustrade.common.constant;

/**
 * 商品状态常量
 * 定义商品在交易平台中的各种状态
 */
public class ProductStatus {

    /**
     * 商品已下架
     */
    public static final int OFF_SALE = 0;

    /**
     * 商品在售中
     */
    public static final int ON_SALE = 1;

    /**
     * 商品已锁定
     */
    public static final int LOCKED = 2;

    /**
     * 商品已售出
     */
    public static final int SOLD = 3;

    /**
     * 商品待审核
     *
     * <p>用户发布商品后默认进入此状态，需管理员审核通过后才能上架（变为 ON_SALE）。
     * 审核不通过则置为 OFF_SALE（下架）。</p>
     */
    public static final int PENDING_REVIEW = 4;
}
