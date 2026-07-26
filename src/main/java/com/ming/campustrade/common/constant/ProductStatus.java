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
     * 审核不通过则置为 REJECTED（已驳回）。</p>
     */
    public static final int PENDING_REVIEW = 4;

    /**
     * 商品已驳回
     *
     * <p>管理员审核不通过时置为此状态（与卖家主动下架 OFF_SALE 区分开）。
     * 卖家可查看驳回原因（reviewRemark），修改后重新提交审核（变为 PENDING_REVIEW）。</p>
     */
    public static final int REJECTED = 5;
}
