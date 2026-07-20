package com.ming.campustrade.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 订单视图对象（VO，View Object），用于向前端返回订单详情/列表数据。
 *
 * <p>相比 {@code Order} 实体，本 VO 额外携带了 {@code buyerNickname}、
 * {@code sellerNickname} 等关联用户信息，由 Service 层根据 buyerId/sellerId
 * 查询用户表后填充，方便前端直接展示买家/卖家昵称。
 * 同时屏蔽了 {@code deleted}、{@code updateTime} 等前端无需关心的内部字段。</p>
 *
 * @author ming
 */
@Data
public class OrderVO {

    /** 订单主键 ID。 */
    private Long id;

    /** 订单编号（业务单号），对外展示给用户。 */
    private String orderNo;

    /** 关联的商品 ID。 */
    private Long productId;

    /** 商品标题快照（下单时的标题）。 */
    private String productTitle;

    /** 商品价格快照（下单时的成交价）。 */
    private BigDecimal productPrice;

    /** 商品图片快照（下单时的主图 URL）。 */
    private String productImage;

    /** 买家用户 ID。 */
    private Long buyerId;

    /** 买家昵称（关联用户表填充，方便前端直接展示）。 */
    private String buyerNickname;

    /** 卖家用户 ID。 */
    private Long sellerId;

    /** 卖家昵称（关联用户表填充，方便前端直接展示）。 */
    private String sellerNickname;

    /** 订单状态：0=待确认，1=已确认，2=已取消。 */
    private Integer status;

    /** 订单创建时间（下单时间）。 */
    private LocalDateTime createTime;
}
