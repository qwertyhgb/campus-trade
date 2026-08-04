package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 活动审核数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于管理员审核活动：通过则活动进入「报名中」，拒绝则进入「审核拒绝」。
 * 状态转换必须经过 {@link com.ming.campustrade.common.constant.ActivityStatus#canTransition} 白名单校验。</p>
 *
 * @author ming
 */
@Data
public class ActivityReviewDTO {

    /** 活动 ID，必填（标识要审核哪条活动）。 */
    @NotNull(message = "活动ID不能为空")
    private Long id;

    /** 是否审核通过：true=通过，false=拒绝。 */
    @NotNull(message = "审核结果不能为空")
    private Boolean pass;

    /**
     * 拒绝原因：拒绝时必填（提示组织者修改方向），通过时为空。
     *
     * <p>跨字段校验（pass=false 时 rejectReason 不能为空）在 Service 层完成，
     * DTO 注解无法表达「字段间依赖关系」。</p>
     */
    @Size(max = 500, message = "拒绝原因不能超过500个字符")
    private String rejectReason;
}
