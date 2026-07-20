package com.ming.campustrade.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/**
 * 商品视图对象（VO，View Object），用于向前端返回商品详情/列表数据。
 *
 * <p>相比 {@code Product} 实体，本 VO 额外携带了 {@code sellerNickname}、
 * {@code sellerAvatar} 等"卖家信息"。这些字段并不存在于商品表中，
 * 而是 Service 层根据 {@code sellerId} 关联查询用户表后填充进来的——
 * 这样前端展示商品时就能直接显示卖家昵称和头像，无需再发一次请求。
 * 同时 VO 也屏蔽了 {@code deleted} 等内部字段，只暴露前端需要的内容。</p>
 *
 * @author ming
 */
@Data
public class ProductVO {

    /** 商品主键 ID。 */
    private Long id;

    /** 商品标题。 */
    private String title;

    /** 商品详细描述。 */
    private String description;

    /** 当前售价（二手价），单位：元。 */
    private BigDecimal price;

    /** 商品原价（参考用），可为空。 */
    private BigDecimal originalPrice;

    /** 商品主图 URL。 */
    private String image;

    /** 所属分类 ID。 */
    private Long categoryId;

    /** 卖家用户 ID。 */
    private Long sellerId;

    /** 卖家昵称（关联用户表填充，方便前端直接展示）。 */
    private String sellerNickname;

    /** 卖家头像 URL（关联用户表填充，方便前端直接展示）。 */
    private String sellerAvatar;

    /** 成色等级：0=全新，1=几乎全新，2=轻微使用，3=明显使用。 */
    private Integer conditionLevel;

    /** 商品状态：0=下架，1=在售，2=锁定，3=已售。 */
    private Integer status;

    /** 浏览次数。 */
    private Integer viewCount;

    /** 商品发布时间。 */
    private LocalDateTime createTime;
}
