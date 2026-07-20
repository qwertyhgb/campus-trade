package com.ming.campustrade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Data;

/**
 * 商品实体类（Entity），与数据库 {@code product} 表一一映射。
 *
 * <p>本表存储校园二手交易中每一件商品的核心信息：标题、描述、价格、图片、
 * 所属分类、卖家、成色、状态等。一条记录代表一件可被浏览/购买的商品。</p>
 *
 * <p>金额字段为什么使用 {@link BigDecimal} 而不是 {@code double}？
 * 因为 {@code double} 是二进制浮点数，无法精确表示 0.1 这样的十进制小数，
 * 在累加/比较时会出现 "0.1 + 0.2 != 0.3" 的精度问题。涉及钱的场景必须使用
 * {@link BigDecimal}，避免对账时出现分级别的误差。</p>
 *
 * @author ming
 */
@Data
public class Product {

    /**
     * 商品主键 ID，对应表中 {@code id} 列。
     */
    private Long id;

    /**
     * 商品标题，例如 "九成新 iPhone 13 128G"，是列表页和搜索结果中最显眼的信息。
     */
    private String title;

    /**
     * 商品详细描述，介绍成色、使用时长、是否有瑕疵等，帮助买家做决策。
     */
    private String description;

    /**
     * 当前售价（二手价），单位：元。使用 {@link BigDecimal} 保证金额精度。
     */
    private BigDecimal price;

    /**
     * 商品原价（参考用），用于在页面上展示 "原价 xxx，现价 xxx" 的对比效果。
     * 可为空，因为有些商品没有明确的原价。
     */
    private BigDecimal originalPrice;

    /**
     * 商品主图的 URL 地址。
     */
    private String image;

    /**
     * 所属分类 ID，关联 {@code category} 表的主键。
     *
     * <p>通过外键 ID 关联而不是直接保存分类名称，是为了避免数据冗余：
     * 分类改名时只需改一处，所有商品自动生效。</p>
     */
    private Long categoryId;

    /**
     * 卖家用户 ID，关联 {@code user} 表的主键，标识这件商品是谁发布的。
     */
    private Long sellerId;

    /**
     * 商品成色等级。
     *
     * <ul>
     *     <li>{@code 0} —— 全新（未拆封/未使用）</li>
     *     <li>{@code 1} —— 几乎全新（仅拆封或极少使用）</li>
     *     <li>{@code 2} —— 轻微使用（有轻微使用痕迹）</li>
     *     <li>{@code 3} —— 明显使用（有明显使用痕迹或磨损）</li>
     * </ul>
     */
    private Integer conditionLevel;

    /**
     * 商品状态。
     *
     * <ul>
     *     <li>{@code 0} —— 下架（卖家主动下架，前台不可见）</li>
     *     <li>{@code 1} —— 在售（正常展示，可被购买）</li>
     *     <li>{@code 2} —— 锁定（已被下单占用，等待买家确认/付款，避免超卖）</li>
     *     <li>{@code 3} —— 已售（交易完成，不再可购买）</li>
     * </ul>
     */
    private Integer status;

    /**
     * 浏览次数，每次进入商品详情页时 +1，可用于 "热门商品" 排序。
     */
    private Integer viewCount;

    /**
     * 商品发布时间，使用 {@link LocalDateTime}（线程安全、API 友好）。
     */
    private LocalDateTime createTime;

    /**
     * 商品最近一次更新时间。
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志位（0=未删除，1=已删除）。
     *
     * <p>{@link TableLogic} 让 MyBatis-Plus 把删除操作转换为
     * {@code UPDATE ... SET deleted = 1}，并自动在所有查询中过滤已删除数据。
     * 这样既能在前台"消失"，又能保留历史以便追溯订单关联的商品。</p>
     */
    @TableLogic
    private Integer deleted;
}
