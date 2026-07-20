package com.ming.campustrade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 订单实体类（Entity），与数据库 {@code order} 表一一映射。
 *
 * <p>为什么需要 {@link TableName} 并写成 {@code "`order`"}？
 * 因为 {@code ORDER} 是 MySQL 的保留关键字（用于 {@code ORDER BY} 排序），
 * 如果直接拿 {@code order} 当表名，生成的 SQL 会报语法错误。
 * 用反引号 {@code `} 把表名包起来，是告诉 MySQL "这是一个标识符，不是关键字"，
 * 从而避免冲突。MyBatis-Plus 默认根据类名推断表名，这里必须显式指定。</p>
 *
 * <p>为什么订单里要冗余保存 {@code productTitle}、{@code productPrice}、
 * {@code productImage} 这些"商品快照"字段（即反范式化 / denormalize）？
 * 因为商品的信息是会变化的：卖家可能改标题、调价格、换图片，甚至删除商品。
 * 但订单作为交易凭证，必须记录"下单那一刻"的商品信息，否则历史订单会随商品
 * 改动而错乱（比如显示一个和当时不一样的价格）。所以这里故意冗余一份快照，
 * 牺牲一点存储空间换取数据的准确性与可追溯性。</p>
 *
 * @author ming
 */
@Data
@TableName("`order`")
public class Order {

    /**
     * 订单主键 ID，对应表中 {@code id} 列。
     */
    private Long id;

    /**
     * 订单编号（业务单号），通常由时间戳 + 随机数生成，对外展示给用户，
     * 便于客服沟通和问题排查。与数据库自增 ID 区分开，避免暴露真实订单量。
     */
    private String orderNo;

    /**
     * 关联的商品 ID，指向 {@code product} 表主键。
     */
    private Long productId;

    /**
     * 商品标题快照：下单时从商品表复制过来，后续商品改名不影响历史订单。
     */
    private String productTitle;

    /**
     * 商品价格快照：记录下单时的成交价，使用 {@link BigDecimal} 保证金额精度。
     */
    private BigDecimal productPrice;

    /**
     * 商品图片快照：记录下单时的商品主图 URL。
     */
    private String productImage;

    /**
     * 买家用户 ID，关联 {@code user} 表，标识谁购买了这件商品。
     */
    private Long buyerId;

    /**
     * 卖家用户 ID，关联 {@code user} 表，标识谁卖出了这件商品。
     */
    private Long sellerId;

    /**
     * 订单状态。
     *
     * <ul>
     *     <li>{@code 0} —— 待确认（已下单，等待卖家/买家确认交易）</li>
     *     <li>{@code 1} —— 已确认（交易完成）</li>
     *     <li>{@code 2} —— 已取消（订单被取消，商品恢复可售）</li>
     * </ul>
     */
    private Integer status;

    /**
     * 订单创建时间（下单时间），使用 {@link LocalDateTime}。
     */
    private LocalDateTime createTime;

    /**
     * 订单最近一次更新时间（确认/取消时会刷新）。
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志位（0=未删除，1=已删除）。
     *
     * <p>{@link TableLogic} 使删除变为 {@code UPDATE ... SET deleted = 1}，
     * 并自动过滤查询。订单作为重要交易凭证，几乎不应物理删除，逻辑删除可保留追溯能力。</p>
     */
    @TableLogic
    private Integer deleted;
}
