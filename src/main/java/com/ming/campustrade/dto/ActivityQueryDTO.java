package com.ming.campustrade.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 活动查询数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于封装活动列表页的「分页 + 多条件筛选」查询参数。
 * 所有字段均为可选：前端不传某条件时，Service 层即不对该条件做过滤。</p>
 *
 * @author ming
 */
@Data
public class ActivityQueryDTO {

    /** 页码，默认第 1 页，最小 1。 */
    @Min(value = 1, message = "页码最小为1")
    private Integer pageNo = 1;

    /** 每页条数，默认 10 条，限制 1~100（防止恶意请求超大分页拖垮数据库）。 */
    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 100, message = "每页条数最大为100")
    private Integer pageSize = 10;

    /**
     * 搜索关键词（模糊匹配活动标题）。
     * 限制长度可以避免有人提交超长字符串，造成无意义的 LIKE 查询和数据库压力。
     */
    @Size(max = 100, message = "搜索关键词不能超过100个字符")
    private String keyword;

    /** 分类筛选。 */
    private Long categoryId;

    /**
     * 状态筛选，见 {@link com.ming.campustrade.common.constant.ActivityStatus}，取值 0~7。
     *
     * <p>注意：公开访问只展示已发布状态；status 为空时 Service 层
     * 自动过滤内部状态。管理员可以显式传入状态查询运营数据。</p>
     */
    @Min(value = 0, message = "活动状态不能小于0")
    @Max(value = 7, message = "活动状态不能大于7")
    private Integer status;

    /**
     * 活动开始时间下界（筛选 start_time ≥ 该时间的活动）。
     *
     * <p>用于"按时间范围筛选活动"，例如只看本周/本月的活动。可选字段，不传则不过滤。</p>
     */
    private LocalDateTime startTimeFrom;

    /**
     * 活动开始时间上界（筛选 start_time ≤ 该时间的活动）。
     *
     * <p>与 {@link #startTimeFrom} 搭配使用，构成 [startTimeFrom, startTimeTo] 闭区间。
     * 可选字段，不传则不过滤。</p>
     */
    private LocalDateTime startTimeTo;
}
