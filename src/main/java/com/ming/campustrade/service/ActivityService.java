package com.ming.campustrade.service;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.ActivityCreateDTO;
import com.ming.campustrade.dto.ActivityQueryDTO;
import com.ming.campustrade.dto.ActivityReviewDTO;
import com.ming.campustrade.dto.ActivityUpdateDTO;
import com.ming.campustrade.entity.Activity;
import com.ming.campustrade.vo.ActivityDetailVO;
import com.ming.campustrade.vo.ActivityListItemVO;

/**
 * 活动业务逻辑接口。
 *
 * <p>当前已覆盖活动的创建、编辑、删除、提交审核、管理员审核、下架，
 * 以及列表分页查询、活动详情、组织者我的活动列表。</p>
 *
 * @author ming
 */
public interface ActivityService extends IService<Activity> {

    /**
     * 创建活动，初始状态为草稿，组织者由当前登录用户确定。
     *
     * @param dto 创建活动参数
     * @return 数据库生成的新活动 ID
     */
    Long createActivity(ActivityCreateDTO dto);

    /**
     * 编辑活动。只有组织者本人或管理员可以编辑，且活动必须处于草稿或审核拒绝状态。
     *
     * @param dto 编辑活动参数
     */
    void updateActivity(ActivityUpdateDTO dto);

    /**
     * 删除活动。使用 MyBatis-Plus 逻辑删除，只有组织者本人或管理员可以删除草稿/审核拒绝活动。
     *
     * @param id 活动 ID
     */
    void deleteActivity(Long id);

    /**
     * 组织者提交活动审核：草稿或审核拒绝 → 待审核。
     *
     * @param id 活动 ID
     */
    void submitReview(Long id);

    /**
     * 管理员审核活动：待审核 → 报名中或审核拒绝。
     *
     * @param dto 审核参数
     */
    void reviewActivity(ActivityReviewDTO dto);

    /**
     * 管理员下架活动：任意非终态 → 已下架。
     *
     * @param id 活动 ID
     */
    void offShelf(Long id);

    /**
     * 分页查询活动列表，支持关键词搜索、分类筛选、状态筛选、活动开始时间范围筛选。
     *
     * <p>为避免 N+1 查询，分类名和组织者昵称采用批量查询后填充。</p>
     *
     * @param dto 查询条件（分页 + 筛选参数）
     * @return 携带分类名、组织者昵称的活动列表分页对象
     */
    IPage<ActivityListItemVO> getActivityPage(ActivityQueryDTO dto);

    /**
     * 查询活动详情（含分类名、组织者昵称、审核信息等完整字段）。
     *
     * @param id 活动 ID
     * @return 活动详情视图对象
     */
    ActivityDetailVO getActivityDetail(Long id);

    /**
     * 查询热门活动榜单，按 Redis 热度分数降序返回。
     *
     * <p>Redis ZSet 只提供“热度排序的 ID”；标题、封面、人数等展示数据仍从 MySQL 批量读取。
     * 只返回公开状态（报名中/报名结束/进行中/已结束）的活动 ——
     * 已删除、已下架或内部状态的活动即使残留在排行榜，数据库过滤后也不会展示。</p>
     *
     * @param limit 期望返回条数（null 或非法值由实现层兜底：小于 1 用默认 10，最大 50）
     * @return 按热度降序的活动列表项；排行榜为空或 Redis 不可用时返回空列表
     */
    List<ActivityListItemVO> getHotActivities(Integer limit);

    /**
     * 查询当前登录用户（组织者）创建的全部活动列表，按创建时间倒序。
     *
     * @return 当前用户的活动列表
     */
    List<ActivityListItemVO> getMyActivities();
}
