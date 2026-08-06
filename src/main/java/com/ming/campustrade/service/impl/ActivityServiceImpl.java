package com.ming.campustrade.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.ActivityStatus;
import com.ming.campustrade.common.constant.ReservationStatus;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.ActivityCreateDTO;
import com.ming.campustrade.dto.ActivityQueryDTO;
import com.ming.campustrade.dto.ActivityReviewDTO;
import com.ming.campustrade.dto.ActivityUpdateDTO;
import com.ming.campustrade.entity.Activity;
import com.ming.campustrade.entity.ActivityCategory;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.event.ActivityReviewedEvent;
import com.ming.campustrade.mapper.ActivityMapper;
import com.ming.campustrade.mapper.ReservationMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.mapper.WaitingListMapper;
import com.ming.campustrade.messaging.NotificationEventPublisher;
import com.ming.campustrade.service.ActivityCacheService;
import com.ming.campustrade.service.ActivityCategoryService;
import com.ming.campustrade.service.ActivityHotRankService;
import com.ming.campustrade.service.ActivityService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.ActivityDetailVO;
import com.ming.campustrade.vo.ActivityListItemVO;
import com.ming.campustrade.vo.ActivityPublicDetailVO;
import com.ming.campustrade.vo.UserVO;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 活动服务实现类 —— 处理活动的创建、编辑、删除和状态流转。
 *
 * <p><b>核心设计：业务规则集中在 Service 层</b><br>
 * 这里集中处理跨字段校验（时间先后）、数据权限校验（只能操作自己的活动）、
 * 状态校验（只有合法状态转换才允许变更），避免直接调用通用的
 * {@code save/updateById/removeById} 绕过业务规则（如把任意活动改成任意状态）。</p>
 *
 * <p><b>三层校验体系（对应三个私有方法）：</b><br>
 * 1. 身份校验：{@link #requireCurrentUser()} —— 当前用户必须已登录<br>
 * 2. 归属校验：{@link #checkOwnerOrAdmin(Activity, UserVO)} —— 只能操作自己创建的活动（管理员除外）<br>
 * 3. 状态校验：{@link #checkEditableStatus(Activity)} —— 只有草稿/审核拒绝可编辑删除</p>
 *
 * @author ming
 */
@Slf4j
@Service
@SuppressWarnings("null")
public class ActivityServiceImpl extends ServiceImpl<ActivityMapper, Activity>
        implements ActivityService {

    /**
     * 对未登录用户公开展示的活动状态。
     *
     * <p>草稿、待审核、审核拒绝属于组织者/审核员之间的内部流程，
     * 已下架活动也不应该继续出现在校园活动大厅中。因此，公开列表和公开详情
     * 只能看到已经发布过的活动：报名中、报名结束、进行中、已结束。</p>
     *
     * <p>使用 Set 的好处是：判断某个状态是否公开时只需要一次快速查找，
     * 而且所有公开状态集中写在这里，后续新增状态时不容易漏改某个接口。</p>
     */
    private static final Set<Integer> PUBLIC_ACTIVITY_STATUSES = Set.of(
            ActivityStatus.ENROLLING,
            ActivityStatus.ENROLL_ENDED,
            ActivityStatus.ONGOING,
            ActivityStatus.FINISHED
    );

    /**
     * 活动详情缓存组件：负责删除活动详情缓存（Cache-Aside 的“写后失效”）以及读取/回填。
     *
     * <p>活动本身的内容或状态一旦变化，Redis 里的旧详情必须删除，
     * 否则用户会持续看到过期数据。本字段只负责缓存的读写删，不改变任何活动数据库业务；
     * evict 内部已吞掉 Redis 异常，删除失败不会影响活动事务。</p>
     */
    private final ActivityCacheService activityCacheService;

    /**
     * 活动 Mapper：详情查询时直接按主键查库。
     *
     * <p>为什么详情查询不复用 ServiceImpl 自带的 getById / 私有 getActivity？<br>
     * 因为缓存流程需要区分三种情况：缓存命中（直接返回）、空值缓存命中（活动确实不存在）、
     * 数据库查不到（写入空值缓存防穿透）。getActivity 是“查不到立即抛异常”的封装，
     * 无法区分“查不到”这一事实，所以详情查询直接走 Mapper 原生 selectById，
     * 由本方法自己决定查不到时如何处理。</p>
     */
    private final ActivityMapper activityMapper;

    /**
     * 活动分类 Service：用于校验分类是否存在（创建/编辑）以及批量查分类名（列表/详情填充）。
     * 通过构造器注入，保证依赖不可变（final）。
     */
    private final ActivityCategoryService activityCategoryService;

    /**
     * 用户 Mapper：用于批量查组织者昵称（列表/详情填充），避免 N+1 查询。
     */
    private final UserMapper userMapper;

    /**
     * 候补 Mapper：活动被下架时，立即让该活动的有效候补失效。
     *
     * <p>这里直接注入 Mapper 是因为下架动作需要和活动状态更新放在同一个事务里，
     * 保证不会出现“活动已经下架，但候补仍然有效”的中间状态。</p>
     */
    private final WaitingListMapper waitingListMapper;

    /**
     * 下架活动时需要同步使有效预约失效。
     *
     * <p>预约不是逻辑删除，而是保留状态历史；因此这里通过 Mapper 批量改为
     * {@code EXPIRED}，并释放 {@code activeMark}，而不是直接删除预约行。</p>
     */
    private final ReservationMapper reservationMapper;

    /**
     * 通知事件发布器：审核完成后，在事务提交后发送 RabbitMQ 事件。
     *
     * <p>只依赖 RabbitTemplate，不会与业务 Service 形成循环依赖。</p>
     */
    private final NotificationEventPublisher notificationEventPublisher;

    /**
     * 活动热度排行榜组件：活动删除/下架后清除排行榜残留（排行榜仅展示，不影响核心业务）。
     *
     * <p>排行榜是纯展示数据：热度只用于“哪些活动更热门”的排序展示，
     * 绝不参与名额判断、状态流转等任何核心业务 —— 那些必须以 MySQL 为准。
     * removeActivity 内部已吞掉 Redis 异常，清理失败不影响活动删除/下架。</p>
     */
    private final ActivityHotRankService activityHotRankService;

    public ActivityServiceImpl(ActivityCacheService activityCacheService,
                               ActivityMapper activityMapper,
                               ActivityCategoryService activityCategoryService,
                               UserMapper userMapper,
                               WaitingListMapper waitingListMapper,
                               ReservationMapper reservationMapper,
                               NotificationEventPublisher notificationEventPublisher,
                               ActivityHotRankService activityHotRankService) {
        this.activityCacheService = activityCacheService;
        this.activityMapper = activityMapper;
        this.activityCategoryService = activityCategoryService;
        this.userMapper = userMapper;
        this.waitingListMapper = waitingListMapper;
        this.reservationMapper = reservationMapper;
        this.notificationEventPublisher = notificationEventPublisher;
        this.activityHotRankService = activityHotRankService;
    }

    // ==================== 创建活动 ====================

    /**
     * 创建活动（组织者接口，需登录）。
     *
     * <p><b>流程：</b>校验登录 → 校验时间先后 → 校验分类存在 → 组装实体（状态/组织者由后端固定）→ 保存</p>
     *
     * <p><b>为什么 status 固定为草稿、organizerId 取当前登录用户？</b><br>
     * 防止前端伪造：如果允许前端传 status，用户可以直接传"报名中(3)"跳过审核；
     * 如果允许前端传 organizerId，用户可以冒用别人的 ID 创建活动。
     * 所以这两个字段只能由后端决定 —— 新活动一律草稿，归属一律是当前登录用户。</p>
     *
     * @param dto 创建活动参数（标题、时间、地点、人数上限等）
     * @throws BusinessException 未登录 / 时间配置不合法 / 分类不存在
     */
    @Override
    public Long createActivity(ActivityCreateDTO dto) {
        // 1. 身份校验：未登录直接拒绝（接口虽已由 Security 拦截，这里防御性兜底）
        UserVO currentUser = requireCurrentUser();

        // 2. 时间校验：报名开始 < 报名结束 ≤ 活动开始 < 活动结束（跨字段校验，DTO 注解做不了）
        validateTime(dto.getStartTime(), dto.getEndTime(),
                dto.getEnrollStartTime(), dto.getEnrollEndTime());

        // 3. 分类校验：分类必须真实存在，防止挂到不存在的分类上
        requireCategory(dto.getCategoryId());

        // 4. 组装实体：只拷贝前端可信的业务字段
        Activity activity = new Activity();
        activity.setTitle(dto.getTitle());
        activity.setDescription(dto.getDescription());
        activity.setLocation(dto.getLocation());
        activity.setCoverImage(dto.getCoverImage());
        activity.setCategoryId(dto.getCategoryId());
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        activity.setEnrollStartTime(dto.getEnrollStartTime());
        activity.setEnrollEndTime(dto.getEnrollEndTime());
        activity.setMaxCount(dto.getMaxCount());

        // 4.1 以下字段一律由后端赋值，前端传了也不认：
        //     currentCount=0（新活动没人报名）、status=草稿（需审核才能报名）、
        //     organizerId=当前用户（归属不可伪造）、deleted=0（未删除）
        activity.setCurrentCount(0);
        activity.setStatus(ActivityStatus.DRAFT);
        activity.setOrganizerId(currentUser.getId());
        activity.setDeleted(0);

        // 5. 保存：save() 执行 INSERT，成功后自增 ID 自动回填到 activity.getId()
        if (!this.save(activity)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动创建失败");
        }
        log.info("创建活动成功：activityId={}, organizerId={}", activity.getId(), currentUser.getId());
        return activity.getId();
    }

    // ==================== 编辑活动 ====================

    /**
     * 编辑活动（组织者本人或管理员，部分更新）。
     *
     * <p><b>什么是部分更新？</b><br>
     * 前端只传要修改的字段，未传的字段（为 null）保持数据库原值 —— 见下方逐个 if 判断。</p>
     *
     * <p><b>流程：</b>身份校验 → 查活动 → 归属校验 → 状态校验 → 时间校验（合并新旧时间后整体校验）
     * → 分类/人数/文本校验 → 逐字段覆盖 → 特殊处理（审核拒绝的活动编辑后回到草稿）→ 写库</p>
     *
     * <p><b>为什么时间要"合并后校验"？</b><br>
     * 部分更新时前端可能只改了开始时间，其他时间沿用原值。
     * 如果把 DTO 的 null 直接传给校验方法会误判"时间缺失"，
     * 所以先用 {@link #valueOrDefault} 把没传的字段替换为数据库原值，再整体校验。</p>
     *
     * @param dto 编辑活动参数（ID 必填，其余可选）
     * @throws BusinessException 活动不存在 / 无权限 / 状态不可编辑 / 时间或人数配置不合法
     */
    @Override
    public void updateActivity(ActivityUpdateDTO dto) {
        // 1. 身份校验
        UserVO currentUser = requireCurrentUser();

        // 2. 查活动（不存在抛 ACTIVITY_NOT_FOUND）
        Activity activity = getActivity(dto.getId());

        // 3. 归属校验：不是自己的活动且不是管理员 → 403（防止越权改别人的活动）
        checkOwnerOrAdmin(activity, currentUser);

        // 4. 状态校验：只有草稿/审核拒绝可编辑（已发布的不能随便改，防止绕过审核改内容）
        checkEditableStatus(activity);

        // 5. 时间校验：没传的时间用数据库原值补齐，再整体校验先后关系
        LocalDateTime startTime = valueOrDefault(dto.getStartTime(), activity.getStartTime());
        LocalDateTime endTime = valueOrDefault(dto.getEndTime(), activity.getEndTime());
        LocalDateTime enrollStartTime = valueOrDefault(dto.getEnrollStartTime(), activity.getEnrollStartTime());
        LocalDateTime enrollEndTime = valueOrDefault(dto.getEnrollEndTime(), activity.getEnrollEndTime());
        validateTime(startTime, endTime, enrollStartTime, enrollEndTime);

        // 6. 分类校验：换了分类才校验（不换分类不需要查库）
        if (dto.getCategoryId() != null) {
            requireCategory(dto.getCategoryId());
        }

        // 7. 人数校验：新上限不能小于当前已报名人数（否则名额为负，无法自圆其说）
        int currentCount = activity.getCurrentCount() == null ? 0 : activity.getCurrentCount();
        if (dto.getMaxCount() != null && dto.getMaxCount() < currentCount) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "最大参与人数不能小于当前报名人数");
        }

        // 8. 文本校验：传了但内容是空白（null 不算，空白算）→ 拒绝
        //    例如前端把标题传成 "   "，存进去会显示空白标题
        validateOptionalText(dto.getTitle(), "活动标题不能为空");
        validateOptionalText(dto.getLocation(), "活动地点不能为空");

        // 9. 逐字段部分更新：只有非 null 才覆盖，null 保持原值
        if (dto.getTitle() != null) {
            activity.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            activity.setDescription(dto.getDescription());
        }
        if (dto.getLocation() != null) {
            activity.setLocation(dto.getLocation());
        }
        if (dto.getCoverImage() != null) {
            activity.setCoverImage(dto.getCoverImage());
        }
        if (dto.getCategoryId() != null) {
            activity.setCategoryId(dto.getCategoryId());
        }
        if (dto.getStartTime() != null) {
            activity.setStartTime(dto.getStartTime());
        }
        if (dto.getEndTime() != null) {
            activity.setEndTime(dto.getEndTime());
        }
        if (dto.getEnrollStartTime() != null) {
            activity.setEnrollStartTime(dto.getEnrollStartTime());
        }
        if (dto.getEnrollEndTime() != null) {
            activity.setEnrollEndTime(dto.getEnrollEndTime());
        }
        if (dto.getMaxCount() != null) {
            activity.setMaxCount(dto.getMaxCount());
        }

        // 10. 特殊处理：审核拒绝的活动被重新编辑后回到草稿，等待再次提交审核
        //     （状态机白名单允许 REJECTED → DRAFT）
        //     同时清空审核信息，避免旧驳回原因误导下一次审核
        if (activity.getStatus() == ActivityStatus.REJECTED) {
            if (!ActivityStatus.canTransition(activity.getStatus(), ActivityStatus.DRAFT)) {
                throw new BusinessException(ResultCode.ACTIVITY_STATUS_ERROR);
            }
            activity.setStatus(ActivityStatus.DRAFT);
            activity.setReviewerId(null);
            activity.setReviewTime(null);
            activity.setRejectReason(null);
        }

        // 11. 写库：updateById 只更新非 null 字段（部分更新落库）
        if (!this.updateById(activity)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动修改失败");
        }
        log.info("修改活动成功：activityId={}, operatorId={}", activity.getId(), currentUser.getId());
        // 活动内容已变化，事务提交后删除旧详情缓存，避免用户继续看到编辑前的详情
        evictActivityCacheAfterCommit(activity.getId());
    }

    // ==================== 删除活动 ====================

    /**
     * 删除活动（组织者本人或管理员）。
     *
     * <p>Activity 实体的 {@code @TableLogic} 会把删除转换为软删除
     * （UPDATE activity SET deleted=1 而非 DELETE），数据保留可追溯。</p>
     *
     * <p><b>为什么只允许删草稿/审核拒绝？</b><br>
     * 已发布（报名中/进行中等）的活动已有用户预约或候补，直接删除会破坏预约数据的一致性，
     * 这类活动只能由管理员下架（后续步骤实现），不能删除。</p>
     *
     * @param id 活动 ID
     * @throws BusinessException 活动不存在 / 无权限 / 状态不可删除
     */
    @Override
    public void deleteActivity(Long id) {
        // 1. 身份校验
        UserVO currentUser = requireCurrentUser();

        // 2. 查活动 + 3. 归属校验（只能删自己的）+ 4. 状态校验（只有草稿/审核拒绝可删）
        Activity activity = getActivity(id);
        checkOwnerOrAdmin(activity, currentUser);
        checkEditableStatus(activity);

        // 5. 软删除（@TableLogic 自动转 UPDATE SET deleted=1）
        if (!this.removeById(activity.getId())) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动删除失败");
        }
        log.info("删除活动成功：activityId={}, operatorId={}", id, currentUser.getId());
        // 活动已逻辑删除，事务提交后删除旧详情缓存，避免已删除活动仍展示在详情页
        evictActivityCacheAfterCommit(id);
        // 活动已删除，事务提交后清除排行榜残留（已删除活动不应继续出现在热门榜）
        publishAfterCommit(() -> activityHotRankService.removeActivity(id));
    }

    // ==================== 提交审核 ====================

    /**
     * 组织者提交审核：草稿/审核拒绝 → 待审核。
     *
     * <p><b>为什么只有组织者本人能提交？（与编辑/删除不同）</b><br>
     * 提交审核是组织者的动作，代表“我对内容满意，请管理员审核”。
     * 管理员不应替组织者提交（管理员只负责审核，两者职责分离），
     * 所以这里用 {@link #checkOwner} 而不是 checkOwnerOrAdmin。</p>
     *
     * <p><b>为什么用 canTransition 而不是直接判断状态？</b><br>
     * 白名单校验的好处：状态规则只定义在 ActivityStatus.TRANSITIONS 一处，
     * 以后新增状态或调整流转路径，只需改白名单，所有调用方自动生效，
     * 不会出现“这里改了那里漏了”的不一致。</p>
     *
     * <p><b>为什么提交时清空审核信息？</b><br>
     * 重新提交意味着上一次审核作废，旧的驳回原因/审核人信息不应残留，
     * 避免误导管理员（看到上次的驳回原因以为这次又驳回了）。</p>
     *
     * @param id 活动 ID
     * @throws BusinessException 未登录 / 活动不存在 / 非本人 / 状态不允许提交审核
     */
    @Override
    public void submitReview(Long id) {
        // 1. 身份校验
        UserVO currentUser = requireCurrentUser();

        // 2. 用 FOR UPDATE 读取并锁住活动行。
        //    下架、预约和取消预约都会修改 activity 表；先加行锁可让它们在同一活动上串行执行，
        //    避免出现“下架后又成功预约”或“下架漏掉并发取消中的预约”等中间状态。
        //    该查询必须位于 @Transactional 方法内，锁会在本事务提交/回滚时自动释放。
        Activity activity = activityMapper.selectByIdForUpdate(id);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }

        // 3. 归属校验：只允许组织者本人（管理员不能代提交，见方法注释）
        checkOwner(activity, currentUser);

        // 4. 状态机校验：当前状态必须是草稿或审核拒绝（白名单 DRAFT/REJECTED → PENDING_REVIEW）
        int from = requireStatus(activity);
        int to = ActivityStatus.PENDING_REVIEW;
        if (!ActivityStatus.canTransition(from, to)) {
            // 例如：待审核的活动重复提交、已结束的活动提交审核 —— 都走这里拒绝
            throw new BusinessException(ResultCode.ACTIVITY_STATUS_ERROR);
        }

        // 5. 更新状态 + 清空上次审核信息（重新提交 = 上次审核作废）
        activity.setStatus(to);
        activity.setReviewerId(null);
        activity.setReviewTime(null);
        activity.setRejectReason(null);

        // 6. 写库
        if (!this.updateById(activity)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "提交审核失败");
        }
        log.info("提交活动审核成功：activityId={}, organizerId={}", id, currentUser.getId());
        // 活动状态已变化，事务提交后删除旧详情缓存（若之前存在公开缓存则一并清除）
        evictActivityCacheAfterCommit(id);
    }

    // ==================== 管理员审核 ====================

    /**
     * 管理员审核：待审核 → 报名中（通过）/ 审核拒绝（驳回）。
     *
     * <p><b>审核留痕（关键设计）：</b><br>
     * 审核通过/拒绝后记录 reviewerId（谁审的）和 reviewTime（何时审的），
     * 拒绝时额外记录 rejectReason（为什么拒）。这些信息是活动审核的审计轨迹，
     * 组织者可以在详情页看到“谁在什么时候驳回了，原因是什么”。</p>
     *
     * <p><b>为什么拒绝时必须填原因？</b><br>
     * 驳回后组织者要按原因修改重新提交。如果没有原因，组织者不知道改什么，
     * 只能反复猜测——这是跨字段校验（pass=false 时 rejectReason 必填），
     * DTO 注解表达不了，所以在 Service 层校验。</p>
     *
     * @param dto 审核参数（活动 ID + 通过/拒绝 + 拒绝原因）
     * @throws BusinessException 参数不完整 / 非管理员 / 活动不在待审核状态 / 拒绝原因缺失 / 状态机拒绝
     */
    @Override
    @Transactional
    public void reviewActivity(ActivityReviewDTO dto) {
        // 1. 参数完整性校验（DTO 的 @Valid 在 Controller 层触发，这里防御性兜底）
        if (dto == null || dto.getId() == null || dto.getPass() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "审核参数不完整");
        }

        // 2. 身份校验 + 审核权限校验（审核员或管理员可以审核）
        UserVO currentUser = requireCurrentUser();
        checkAuditorOrAdmin(currentUser);

        // 3. 查活动
        Activity activity = getActivity(dto.getId());

        // 4. 前置校验：必须先处于待审核状态（已经审过的不能重复审）
        int from = requireStatus(activity);
        if (from != ActivityStatus.PENDING_REVIEW) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_PENDING_REVIEW);
        }

        // 5. 按审核结果确定目标状态：通过 → 报名中，拒绝 → 审核拒绝
        int to = dto.getPass() ? ActivityStatus.ENROLLING : ActivityStatus.REJECTED;

        // 6. 状态机白名单校验（PENDING_REVIEW → ENROLLING / REJECTED）
        if (!ActivityStatus.canTransition(from, to)) {
            throw new BusinessException(ResultCode.ACTIVITY_STATUS_ERROR);
        }

        // 7. 跨字段校验：拒绝时必须填写拒绝原因（trim 去首尾空格后判断）
        if (!dto.getPass() && !StringUtils.hasText(dto.getRejectReason())) {
            throw new BusinessException(ResultCode.ACTIVITY_REJECT_REASON_REQUIRED);
        }

        // 8. 更新状态 + 审核留痕（谁审的、何时审的、为什么拒）
        activity.setStatus(to);
        activity.setReviewerId(currentUser.getId());
        activity.setReviewTime(LocalDateTime.now());
        // 通过时清空 rejectReason（防止旧驳回原因残留），拒绝时存原因
        activity.setRejectReason(dto.getPass() ? null : dto.getRejectReason().trim());

        // 9. 写库
        if (!this.updateById(activity)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动审核失败");
        }

        // 10. 事务提交后发送“活动审核结果”通知事件
        // 接收人 = 活动组织者；消费者根据 passed 自动区分通知类型（5 通过 / 6 拒绝）
        // 必须 afterCommit：审核事务回滚时不发消息，避免虚假通知
        ActivityReviewedEvent event = ActivityReviewedEvent.create(
                activity.getOrganizerId(), dto.getId(), dto.getPass(),
                dto.getPass() ? null : dto.getRejectReason().trim());
        publishAfterCommit(() -> {
            try {
                notificationEventPublisher.publishActivityReviewed(event);
            } catch (Exception e) {
                // 通知是辅助功能，发送失败不能影响已提交的审核主流程
                log.error("活动审核通知发送失败（不影响审核）：eventId={}", event.getEventId(), e);
            }
        });

        // 11. 事务提交后删除活动详情缓存（单独注册一个提交后回调）
        //     审核通过/拒绝都会改变活动状态和审核信息，旧缓存必须失效；
        //     不写进上面通知的 try-catch：Redis 删除与消息发送互不干扰，
        //     evict 内部已吞掉异常，删除失败也不会影响审核事务
        evictActivityCacheAfterCommit(dto.getId());

        log.info("审核活动成功：activityId={}, reviewerId={}, pass={}",
                dto.getId(), currentUser.getId(), dto.getPass());
    }

    /**
     * 在当前事务成功提交后执行任务（事务提交后发消息的通用方法）。
     *
     * <p>afterCommit 保证：只有事务真正提交成功才发送消息；
     * 事务回滚则任务不执行，避免产生虚假通知。</p>
     *
     * @param task 事务提交后要执行的任务（通常是发送通知事件）
     */
    private void publishAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 防御性兜底：调用方没有开启事务时直接执行
            task.run();
        }
    }

    /**
     * 活动写操作成功后，在事务提交后删除该活动的详情缓存（Cache-Aside 的“写后失效”）。
     *
     * <p><b>为什么必须提交后删除？</b><br>
     * 1. 如果先删缓存、随后数据库事务回滚，缓存没了虽然不影响正确性，
     *    但相当于白白丢弃了一次有效缓存，下一次访问要多查一次 MySQL（不必要的缓存穿透）；
     * 2. 更关键的是，数据库没有成功就不应该对缓存做“数据已变化”的操作。
     * 所以统一复用 {@link #publishAfterCommit}，只有事务真正提交后才删缓存。</p>
     *
     * <p>ActivityCacheService.evict() 内部已记录日志并吞掉 Redis 异常，
     * 这里不需要额外 try-catch —— Redis 删除失败绝不能影响活动事务本身。</p>
     *
     * @param activityId 活动 ID
     */
    private void evictActivityCacheAfterCommit(Long activityId) {
        publishAfterCommit(() -> activityCacheService.evict(activityId));
    }

    // ==================== 管理员下架 ====================

    /**
     * 管理员下架：任意非终态 → 已下架。
     *
     * <p><b>为什么任意非终态都能下架？</b><br>
     * 下架是管理员的“应急手段”：活动内容违规、组织者被举报等情况需要立即停止报名。
     * 所以白名单里 ENROLLING/ENROLL_ENDED/ONGOING 都允许 → OFF_SHELF
     * （已结束/已下架是终态，无需也不允许再操作）。</p>
     *
     * <p><b>下架后用户还能预约吗？</b><br>
     * 不能。OFF_SHELF 是终态，预约接口会校验活动状态（阶段 4 实现），
     * 只有“报名中”的活动允许预约，下架即停止一切报名行为。</p>
     *
     * @param id 活动 ID
     * @throws BusinessException 未登录 / 非管理员 / 活动不存在 / 状态为终态不可下架
     */
    @Override
    @Transactional
    public void offShelf(Long id) {
        // 1. 身份校验 + 管理员校验
        UserVO currentUser = requireCurrentUser();
        checkAdmin(currentUser);

        // 2. 查活动
        Activity activity = getActivity(id);

        // 3. 状态机校验：非终态 → 已下架（草稿/待审核也可下架，终态会拒绝）
        int from = requireStatus(activity);
        int to = ActivityStatus.OFF_SHELF;
        if (!ActivityStatus.canTransition(from, to)) {
            throw new BusinessException(ResultCode.ACTIVITY_STATUS_ERROR);
        }

        // 4. 条件更新活动状态，并将已占用人数归零。
        //    虽然第 2 步已经持有行锁，仍在 WHERE 中带上原状态作为最后一道保护：
        //    只有状态仍是刚才读到的 from 时才允许下架。下架后所有有效预约都会在下面失效，
        //    所以 currentCount 也必须归零，保证 activity 表的汇总值和预约表一致。
        LambdaUpdateWrapper<Activity> offShelfWrapper = new LambdaUpdateWrapper<>();
        offShelfWrapper.eq(Activity::getId, id)
                .eq(Activity::getStatus, from)
                .set(Activity::getStatus, to)
                .set(Activity::getCurrentCount, 0);
        if (activityMapper.update(null, offShelfWrapper) != 1) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "活动下架失败");
        }

        // 5. 活动、有效预约、候补状态必须一起成功或一起失败。
        //    预约的 EXPIRED 状态用于表达“不是用户主动取消，而是活动下架导致失效”。
        //    active_mark 置为 NULL 后，唯一索引不会再把这条历史失效记录当成“有效预约”；
        //    同时失效记录仍保留在库中，用户可以查看完整历史。
        int expiredReservationRows = reservationMapper.expireActiveReservationsByActivityId(id);
        if (expiredReservationRows > 0) {
            log.info("活动下架后预约自动失效：activityId={}, 影响 {} 条，失效状态={}",
                    id, expiredReservationRows, ReservationStatus.EXPIRED);
        }

        // 候补记录同理：只更新 WAITING + active_mark=1，已补位/已取消/已失效的历史保持不变。
        int expiredRows = waitingListMapper.expireActiveWaitingByActivityId(id);
        if (expiredRows > 0) {
            log.info("活动下架后候补自动失效：activityId={}, 影响 {} 条", id, expiredRows);
        }
        log.info("下架活动成功：activityId={}, operatorId={}", id, currentUser.getId());
        // 活动状态已变为终态，事务提交后删除旧详情缓存，避免已下架活动仍展示在详情页
        evictActivityCacheAfterCommit(id);
        // 活动已下架，事务提交后清除排行榜残留（下架活动不应继续出现在热门榜）
        publishAfterCommit(() -> activityHotRankService.removeActivity(id));
    }

    // ==================== 列表/详情/我的活动 ====================

    /**
     * 分页查询活动列表（支持关键词、分类、状态、时间范围筛选）。
     *
     * <p><b>避免 N+1 查询的关键</b>：先一次性查出当前页的活动记录，再收集所有不重复的
     * categoryId / organizerId，分别用 {@code listByIds} / {@code selectByIds} 各发一条 SQL
     * 批量查回分类名和昵称，构建 id→name 的 Map，最后在内存里逐条填充 VO。
     * 若在循环里对每条记录单独查分类和用户，N 条记录会触发 2N 次额外 SQL，列表页性能会很差。</p>
     *
     * <p><b>为什么不能简单地只排除 OFF_SHELF？</b><br>
     * 草稿、待审核、审核拒绝也属于内部状态，不能因为它们还没有下架就暴露给匿名用户。
     * 普通访问只允许查询公开状态；管理员如果需要查看内部状态，必须显式传入状态。</p>
     *
     * @param dto 查询条件
     * @return 活动列表分页对象（携带分类名、组织者昵称）
     */
    @Override
    public IPage<ActivityListItemVO> getActivityPage(ActivityQueryDTO dto) {
        // Service 也要防御性处理参数，避免绕过 Controller @Valid 直接调用时出现空指针。
        if (dto == null) {
            dto = new ActivityQueryDTO();
        }
        validatePageQuery(dto);

        // 1. 构建分页对象：页码和每页条数已经完成边界校验（1~100）
        Page<Activity> page = new Page<>(dto.getPageNo(), dto.getPageSize());

        // 2. 构建动态查询条件：只在对应参数非空时追加 where 子句
        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(dto.getKeyword())) {
            // 关键词模糊匹配标题（LIKE '%keyword%'）
            wrapper.like(Activity::getTitle, dto.getKeyword().trim());
        }
        if (dto.getCategoryId() != null) {
            wrapper.eq(Activity::getCategoryId, dto.getCategoryId());
        }
        boolean admin = isAdmin(UserHolder.getUserVO());
        if (dto.getStatus() != null) {
            // 公开接口也允许按状态筛选，但普通用户只能筛选公开状态。
            // 这样即使前端手动拼接 ?status=0，也不会查出草稿活动。
            if (!admin && !isPublicActivityStatus(dto.getStatus())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "只能查询已公开的活动状态");
            }
            wrapper.eq(Activity::getStatus, dto.getStatus());
        } else {
            if (admin) {
                // 管理员未指定状态时查看所有非终态活动，便于后台处理审核/运营任务。
                wrapper.ne(Activity::getStatus, ActivityStatus.OFF_SHELF);
            } else {
                // 匿名用户或普通用户只看到公开状态，避免内部审核状态泄露。
                wrapper.in(Activity::getStatus, PUBLIC_ACTIVITY_STATUSES);
            }
        }
        if (dto.getStartTimeFrom() != null) {
            // start_time >= startTimeFrom
            wrapper.ge(Activity::getStartTime, dto.getStartTimeFrom());
        }
        if (dto.getStartTimeTo() != null) {
            // start_time <= startTimeTo
            wrapper.le(Activity::getStartTime, dto.getStartTimeTo());
        }
        // 按创建时间倒序（最新活动排前面），可命中 idx_status_create 索引避免 filesort
        wrapper.orderByDesc(Activity::getCreateTime);

        // 3. 执行分页查询（MyBatis-Plus 自动追加 LIMIT 和 COUNT）
        this.page(page, wrapper);

        // 4. 批量查分类名和组织者昵称，构建 id→name 的 Map（避免 N+1）
        List<Activity> records = page.getRecords();
        Map<Long, String> categoryNameMap = loadCategoryNameMap(records);
        Map<Long, String> organizerNicknameMap = loadOrganizerNicknameMap(records);

        // 5. 逐条转换为 VO（从 Map 取分类名/昵称，O(1) 查找，无额外 SQL）
        List<ActivityListItemVO> voList = records.stream()
                .map(activity -> convertToActivityVO(activity, categoryNameMap, organizerNicknameMap))
                .collect(Collectors.toList());

        // 6. 构建返回的分页对象：保留原分页元信息（current/size/total），替换 records 为 VO 列表
        Page<ActivityListItemVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 查询活动详情（含分类名、组织者昵称等展示字段）。
     *
     * <p>详情页相比列表需要更多信息：description、报名时间段等。
     * 审核留痕只返回给活动组织者和管理员，避免公开接口泄露审核人员信息及内部处理原因。
     * 分类名和组织者昵称各发一次单条查询即可（详情页只有一条记录，无需批量）。</p>
     *
     * <p><b>Cache-Aside 读流程（先缓存、后数据库）：</b></p>
     * <ol>
     *   <li><b>读公开缓存</b>：缓存命中且当前用户不需要审核信息 → 直接返回（零 SQL）</li>
     *   <li><b>查空值缓存</b>：防缓存穿透 —— 活动此前被确认不存在时不再查 MySQL</li>
     *   <li><b>查 MySQL</b>：查不到 → 写空值缓存；查到 → 权限校验 + 组装 + 回填缓存</li>
     * </ol>
     *
     * <p><b>为什么组织者/管理员必须绕过公开缓存回查 MySQL？</b><br>
     * 公开缓存（ActivityPublicDetailVO）不含审核留痕，是所有访问者共用的安全数据。
     * 如果组织者/管理员也直接返回缓存，他们会丢失 reviewerId / reviewTime / rejectReason；
     * 所以这两类人即使缓存命中，也必须继续查 MySQL，由 buildActivityDetail 重新组装
     * 携带审核信息的完整详情。</p>
     *
     * @param id 活动 ID
     * @return 活动详情视图对象
     * @throws BusinessException 活动不存在（含空值缓存命中）
     */
    @Override
    public ActivityDetailVO getActivityDetail(Long id) {
        // 0. 获取当前登录用户（匿名访问时为 null，不影响后续判断）
        UserVO currentUser = UserHolder.getUserVO();

        // 1. 先读公开详情缓存（Redis 只加速读，MySQL 才是最终依据）
        ActivityPublicDetailVO cachedDetail = activityCacheService.getCachedDetail(id);
        if (cachedDetail != null) {
            // 缓存命中：
            // - 普通访问者（非管理员、非组织者）直接复用缓存，省掉全部 SQL；
            // - 管理员和活动组织者即使缓存命中也要绕过（见 mustBypassPublicCache），
            //   继续走下面的 MySQL 查询，因为他们需要看到审核留痕。
            if (!mustBypassPublicCache(currentUser, cachedDetail)) {
                return fromPublicCacheDetail(cachedDetail);
            }
        } else if (activityCacheService.isNullCached(id)) {
            // 2. 详情缓存未命中时检查空值缓存：之前已确认过“活动不存在”，
            //    直接返回不存在，不再查 MySQL —— 这就是防缓存穿透的关键。
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }

        // 3. 查 MySQL（不复用 getActivity：这里需要区分“查不到”并自行处理）
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            // 活动确实不存在：写入空值缓存（短 TTL），
            // 防止恶意/误请求反复穿透到数据库。
            activityCacheService.cacheNull(id);
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }

        // 4. 权限边界：匿名用户只能看公开状态；组织者本人和管理员可以查看自己的内部状态。
        //    对非公开活动统一返回“活动不存在”，而不是提示“你没有权限”，
        //    这样可以避免别人通过遍历 ID 探测到草稿或待审核活动的存在。
        //    注意：这里绝不能写空值缓存 —— 活动真实存在，只是当前用户无权查看，
        //    写空值缓存会导致其他有权用户也看到“不存在”。
        boolean canViewPrivate = canViewPrivateActivity(activity, currentUser);
        if (!isPublicActivityStatus(activity.getStatus()) && !canViewPrivate) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }

        // 5. 组装详情 VO：能否携带审核信息由权限判断结果决定（组织者本人/管理员才有审核留痕）
        ActivityDetailVO detail = buildActivityDetail(activity, canViewPrivate);

        // 6. 回填公开缓存：只缓存公开状态的活动。
        //    toPublicCacheDetail 只复制公开字段，审核信息永远不会进入 Redis；
        //    草稿/待审核等内部状态的活动即使组织者本人查看，也不回填，
        //    否则缓存被普通用户命中就会泄露内部数据。
        if (isPublicActivityStatus(activity.getStatus())) {
            activityCacheService.cacheDetail(id, toPublicCacheDetail(detail));
        }

        return detail;
    }

    /**
     * 组装活动详情 VO（按访问者身份决定是否携带审核信息）。
     *
     * <p>把“查活动 + 判权限”（getActivityDetail）和“组装 VO”（本方法）拆开，
     * 是因为下一步接入 Redis 缓存时需要复用：缓存未命中时仍要完整查库组装，
     * 而是否带审核信息只取决于调用方传入的 includeReviewInfo，组装逻辑本身不需要变。</p>
     *
     * <p><b>为什么审核信息要按 includeReviewInfo 条件返回？</b><br>
     * reviewerId / reviewTime / rejectReason 是审核过程的内部留痕：
     * 组织者本人需要看到“谁在什么时候驳回了、原因是什么”，便于修改后重新提交；
     * 普通用户和匿名访客则不应看到审核过程，否则等于泄露了审核员的身份。
     * 所以这三个字段是否填充，由调用方根据权限判断结果决定。</p>
     *
     * @param activity          活动实体（已确认存在且允许访问）
     * @param includeReviewInfo true=填充审核信息（组织者本人/管理员）；false=不填充（公开访问）
     * @return 活动详情 VO
     */
    private ActivityDetailVO buildActivityDetail(Activity activity, boolean includeReviewInfo) {
        // 1. 复制活动基础字段：逐字段拷贝（避免 BeanUtils 反射，类型更安全、可读性更好）
        ActivityDetailVO vo = new ActivityDetailVO();
        vo.setId(activity.getId());
        vo.setTitle(activity.getTitle());
        vo.setDescription(activity.getDescription());
        vo.setLocation(activity.getLocation());
        vo.setCoverImage(activity.getCoverImage());
        vo.setCategoryId(activity.getCategoryId());
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setEnrollStartTime(activity.getEnrollStartTime());
        vo.setEnrollEndTime(activity.getEnrollEndTime());
        vo.setMaxCount(activity.getMaxCount());
        vo.setCurrentCount(activity.getCurrentCount());
        // 候补人数是展示数据：只统计当前 WAITING 且 active_mark=1 的有效候补。
        Integer waitingListCount = waitingListMapper.countActiveWaitingByActivityId(activity.getId());
        vo.setWaitingListCount(waitingListCount == null ? 0 : waitingListCount);
        vo.setStatus(activity.getStatus());
        vo.setOrganizerId(activity.getOrganizerId());
        if (includeReviewInfo) {
            // 审核人、审核时间、驳回原因是内部审核信息，只对组织者本人/管理员返回。
            vo.setReviewerId(activity.getReviewerId());
            vo.setReviewTime(activity.getReviewTime());
            vo.setRejectReason(activity.getRejectReason());
        }
        vo.setCreateTime(activity.getCreateTime());

        // 2. 填充分类名：单条查询（详情页只有一条记录）
        if (activity.getCategoryId() != null) {
            ActivityCategory category = activityCategoryService.getById(activity.getCategoryId());
            vo.setCategoryName(category == null ? null : category.getName());
        }

        // 3. 填充组织者昵称：单条查询
        if (activity.getOrganizerId() != null) {
            User organizer = userMapper.selectById(activity.getOrganizerId());
            vo.setOrganizerNickname(organizer == null ? null : organizer.getNickname());
        }

        return vo;
    }

    /**
     * 完整详情 → 公开缓存详情（只复制公开字段）。
     *
     * <p><b>为什么必须逐字段复制而不是复用同一个对象？</b><br>
     * ActivityDetailVO 是“按访问者身份组装”的接口返回对象，可能携带审核信息；
     * ActivityPublicDetailVO 是所有访问者共用的 Redis 数据对象。
     * 两者语义不同，即使字段看起来大部分相同也不能混用：
     * 即使传入的 detail 是组织者/管理员看到的完整版本，也绝不能把
     * reviewerId / reviewTime / rejectReason 带进缓存 ——
     * 否则匿名用户通过详情接口就能看到审核人的身份和驳回原因，属于信息泄露。
     * 这里只复制公开字段，从代码上杜绝审核信息进入缓存的可能。</p>
     *
     * @param detail 完整详情 VO（可能是带审核信息的内部版本）
     * @return 公开缓存详情 VO（不含任何审核字段）
     */
    private ActivityPublicDetailVO toPublicCacheDetail(ActivityDetailVO detail) {
        ActivityPublicDetailVO cached = new ActivityPublicDetailVO();
        cached.setId(detail.getId());
        cached.setTitle(detail.getTitle());
        cached.setDescription(detail.getDescription());
        cached.setLocation(detail.getLocation());
        cached.setCoverImage(detail.getCoverImage());
        cached.setCategoryId(detail.getCategoryId());
        cached.setCategoryName(detail.getCategoryName());
        cached.setStartTime(detail.getStartTime());
        cached.setEndTime(detail.getEndTime());
        cached.setEnrollStartTime(detail.getEnrollStartTime());
        cached.setEnrollEndTime(detail.getEnrollEndTime());
        cached.setMaxCount(detail.getMaxCount());
        cached.setCurrentCount(detail.getCurrentCount());
        cached.setWaitingListCount(detail.getWaitingListCount());
        cached.setStatus(detail.getStatus());
        cached.setOrganizerId(detail.getOrganizerId());
        cached.setOrganizerNickname(detail.getOrganizerNickname());
        cached.setCreateTime(detail.getCreateTime());
        // 注意：reviewerId / reviewTime / rejectReason 一律不复制，参见方法注释
        return cached;
    }

    /**
     * 公开缓存详情 → 接口详情（缓存命中时使用）。
     *
     * <p><b>为什么审核字段保持 null？</b><br>
     * 缓存命中的详情来自 Redis，是面向所有访问者的公开版本，本身就不含审核信息，
     * 不能凭缓存内容推断任何审核结论。所以这里只复制公开字段，
     * 审核字段（reviewerId / reviewTime / rejectReason）不设置、保持 null。
     * 组织者/管理员查看自己的活动时，缓存未命中仍会走
     * {@link #buildActivityDetail buildActivityDetail(activity, true)} 完整组装，
     * 审核信息不会因此缺失。</p>
     *
     * @param cachedDetail 公开缓存详情 VO
     * @return 接口详情 VO（审核字段为 null）
     */
    private ActivityDetailVO fromPublicCacheDetail(ActivityPublicDetailVO cachedDetail) {
        ActivityDetailVO detail = new ActivityDetailVO();
        detail.setId(cachedDetail.getId());
        detail.setTitle(cachedDetail.getTitle());
        detail.setDescription(cachedDetail.getDescription());
        detail.setLocation(cachedDetail.getLocation());
        detail.setCoverImage(cachedDetail.getCoverImage());
        detail.setCategoryId(cachedDetail.getCategoryId());
        detail.setCategoryName(cachedDetail.getCategoryName());
        detail.setStartTime(cachedDetail.getStartTime());
        detail.setEndTime(cachedDetail.getEndTime());
        detail.setEnrollStartTime(cachedDetail.getEnrollStartTime());
        detail.setEnrollEndTime(cachedDetail.getEnrollEndTime());
        detail.setMaxCount(cachedDetail.getMaxCount());
        detail.setCurrentCount(cachedDetail.getCurrentCount());
        detail.setWaitingListCount(cachedDetail.getWaitingListCount());
        detail.setStatus(cachedDetail.getStatus());
        detail.setOrganizerId(cachedDetail.getOrganizerId());
        detail.setOrganizerNickname(cachedDetail.getOrganizerNickname());
        detail.setCreateTime(cachedDetail.getCreateTime());
        return detail;
    }

    /**
     * 查询当前登录用户（组织者）创建的全部活动，按创建时间倒序。
     *
     * <p>组织者进入"我的活动"页面使用，仅返回自己创建的活动，不限制状态
     * （草稿、待审核、报名中等都展示，方便组织者统一管理）。</p>
     *
     * @return 当前用户的活动列表
     * @throws BusinessException 未登录
     */
    @Override
    public List<ActivityListItemVO> getMyActivities() {
        // 1. 身份校验：获取当前登录用户 ID
        UserVO currentUser = requireCurrentUser();

        // 2. 查询当前用户创建的全部活动，按创建时间倒序
        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Activity::getOrganizerId, currentUser.getId())
                .orderByDesc(Activity::getCreateTime);
        List<Activity> activities = this.list(wrapper);

        // 3. 批量查分类名和组织者昵称（复用列表查询的批量方法，避免 N+1）
        Map<Long, String> categoryNameMap = loadCategoryNameMap(activities);
        Map<Long, String> organizerNicknameMap = loadOrganizerNicknameMap(activities);

        // 4. 转换为 VO 列表
        return activities.stream()
                .map(activity -> convertToActivityVO(activity, categoryNameMap, organizerNicknameMap))
                .collect(Collectors.toList());
    }

    /**
     * 查询热门活动榜单，按 Redis 热度分数降序返回。
     *
     * <p><b>Redis 和 MySQL 各司其职：</b><br>
     * Redis ZSet 只提供“热度排序的 ID”（谁热门、排第几）；
     * 标题、封面、人数等展示数据仍然从 MySQL 批量读取（数据源头）。
     * 两者组合：Redis 定顺序，MySQL 出内容。</p>
     *
     * <p><b>为什么不能用数据库查询结果的顺序？</b><br>
     * MySQL 的 IN 查询结果<b>没有顺序保证</b>（返回顺序由执行计划决定），
     * 直接使用会丢失热度排行。正确做法：按 Redis 返回的 ID 顺序遍历，
     * 从 Map 中 O(1) 取活动组装 —— 顺序永远以 Redis 榜单为准。</p>
     *
     * <p><b>数据库过滤是最后防线：</b><br>
     * 即使 Redis 残留了已删除/已下架/内部状态的活动，这里也会过滤掉，
     * 绝不把不可展示的活动返回给用户；同时顺手清理排行榜残留（读取时自愈），
     * Redis 删除失败也不影响本次返回结果。</p>
     *
     * @param limit 期望返回条数（null 或非法值由 ActivityHotRankService 兜底）
     * @return 按热度降序的活动列表项；排行榜为空或 Redis 不可用时返回空列表
     */
    @Override
    public List<ActivityListItemVO> getHotActivities(Integer limit) {
        // 1. 从 Redis ZSet 获取热度排序的活动 ID（按分数降序，已做 limit 保护）
        //    Redis 不可用时 ActivityHotRankService 内部降级返回空列表，
        //    本接口也就降级为“暂无热门榜”，不影响活动主业务。
        List<Long> hotIds = activityHotRankService.getHotActivityIds(limit);
        if (hotIds.isEmpty()) {
            return List.of();
        }

        // 2. 一次批量查库（selectByIds 拼成 WHERE id IN (...)，一条 SQL），
        //    禁止对每个 ID 单独 selectById —— 那是典型的 N+1 查询。
        List<Activity> activities = activityMapper.selectByIds(hotIds);
        if (activities.isEmpty()) {
            return List.of();
        }

        // 3. 建立 activityId → Activity 的 Map：后面按热度顺序遍历时 O(1) 查找
        Map<Long, Activity> activityMap = new HashMap<>();
        for (Activity activity : activities) {
            activityMap.put(activity.getId(), activity);
        }

        // 4. 按 Redis 热度顺序组装，只保留公开状态的活动
        //    （顺序以 hotIds 为准，而不是数据库返回顺序！）
        List<Activity> publicActivities = new ArrayList<>();
        for (Long hotId : hotIds) {
            Activity activity = activityMap.get(hotId);
            if (activity == null) {
                // 排行榜残留了已删除/不存在的活动：顺手自愈清除，
                // 即使 Redis 删除失败也不影响本次返回（removeActivity 内部已吞异常）
                activityHotRankService.removeActivity(hotId);
                continue;
            }
            if (!isPublicActivityStatus(activity.getStatus())) {
                // 非公开状态（草稿/待审核/审核拒绝/已下架）不能展示：
                // 数据库过滤是最后防线，即使 Redis 残留也不泄露给用户；
                // 同样顺手清理，避免下次再查到无效 ID。
                activityHotRankService.removeActivity(hotId);
                continue;
            }
            publicActivities.add(activity);
        }
        if (publicActivities.isEmpty()) {
            return List.of();
        }

        // 5. 批量查分类名和组织者昵称（复用列表查询的批量方法，避免 N+1）
        Map<Long, String> categoryNameMap = loadCategoryNameMap(publicActivities);
        Map<Long, String> organizerNicknameMap = loadOrganizerNicknameMap(publicActivities);

        // 6. 逐条转换为 VO（复用列表转换方法，保证与列表页展示字段一致）
        return publicActivities.stream()
                .map(activity -> convertToActivityVO(activity, categoryNameMap, organizerNicknameMap))
                .collect(Collectors.toList());
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 按 ID 查活动，不存在直接抛异常。
     *
     * <p>封装"判空 + 查库 + 不存在报错"三步，避免每个业务方法重复写。
     * 同时防御性校验 id 非空：id 来自路径变量时通常不会为空，但调用方可能传 null。</p>
     *
     * @param id 活动 ID
     * @return 活动实体
     * @throws BusinessException id 为空或活动不存在
     */
    private Activity getActivity(Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动ID不能为空");
        }
        Activity activity = this.getById(id);
        if (activity == null) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }

    /**
     * 状态校验：只有「草稿」和「审核拒绝」允许编辑/删除。
     *
     * <p>为什么？草稿还没提交审核，随便改；审核拒绝的允许修改后重新提交。
     * 其余状态（待审核、报名中、进行中等）已进入业务流程，改/删会破坏审核或预约数据。</p>
     *
     * @param activity 活动实体
     * @throws BusinessException 状态不可编辑/删除
     */
    private void checkEditableStatus(Activity activity) {
        Integer status = activity.getStatus();
        if (status == null || (status != ActivityStatus.DRAFT && status != ActivityStatus.REJECTED)) {
            throw new BusinessException(ResultCode.ACTIVITY_NOT_EDITABLE);
        }
    }

    /**
     * 归属校验：只有活动组织者本人或管理员可以操作。
     *
     * <p>防止越权：用户 A 不能编辑/删除用户 B 创建的活动。
     * 组织者判断用 ID 精确匹配（owner）；管理员走 {@link #isAdmin} 双来源判断。</p>
     *
     * @param activity    活动实体
     * @param currentUser 当前登录用户
     * @throws BusinessException 非本人且非管理员
     */
    private void checkOwnerOrAdmin(Activity activity, UserVO currentUser) {
        boolean owner = currentUser.getId().equals(activity.getOrganizerId());
        if (!owner && !isAdmin(currentUser)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 归属校验（仅本人）：提交审核只允许组织者本人操作。
     *
     * <p>与 {@link #checkOwnerOrAdmin} 的区别：这里是严格本人校验，管理员也不行。
     * 因为提交审核代表组织者的自主意愿（“我确认内容无误，请审核”），
     * 管理员不应代提交 —— 审核流程的职责分离。</p>
     *
     * @param activity    活动实体
     * @param currentUser 当前登录用户
     * @throws BusinessException 非本人
     */
    private void checkOwner(Activity activity, UserVO currentUser) {
        if (!currentUser.getId().equals(activity.getOrganizerId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 管理员校验：当前用户必须是管理员（走 {@link #isAdmin} 双来源判断）。
     *
     * <p>用于审核、下架等仅管理员可执行的操作。
     * 虽然 Controller 层已有 @PreAuthorize("hasRole('ADMIN')")，
     * 这里仍做防御性校验（单元测试直接调用 Service 时不会经过 Security）。</p>
     *
     * @param currentUser 当前登录用户
     * @throws BusinessException 非管理员
     */
    private void checkAdmin(UserVO currentUser) {
        if (!isAdmin(currentUser)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    /**
     * 审核权限校验：审核员或管理员均可审核，管理员具备审核员的权限范围。
     */
    private void checkAuditorOrAdmin(UserVO currentUser) {
        if (isAdmin(currentUser) || hasSecurityRole("AUDITOR")) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private boolean hasSecurityRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }

    /**
     * 状态非空校验：活动状态为 null 时拒绝操作。
     *
     * <p>status 是状态机的输入，null 状态无法进行白名单校验（canTransition 会误判），
     * 先在这里拦截，返回明确的业务错误而不是隐藏的空指针。</p>
     *
     * @param activity 活动实体
     * @return 活动状态值（保证非 null）
     * @throws BusinessException 状态为 null
     */
    private int requireStatus(Activity activity) {
        if (activity.getStatus() == null) {
            throw new BusinessException(ResultCode.ACTIVITY_STATUS_ERROR);
        }
        return activity.getStatus();
    }

    /**
     * 校验分页查询参数，防止直接调用 Service 时绕过 DTO 的 Bean Validation。
     */
    private void validatePageQuery(ActivityQueryDTO dto) {
        if (dto.getPageNo() == null || dto.getPageNo() < 1
                || dto.getPageSize() == null || dto.getPageSize() < 1 || dto.getPageSize() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分页参数不合法");
        }
        if (dto.getCategoryId() != null && dto.getCategoryId() <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类ID不合法");
        }
        if (dto.getStatus() != null && (dto.getStatus() < ActivityStatus.DRAFT
                || dto.getStatus() > ActivityStatus.OFF_SHELF)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动状态不合法");
        }
        if (dto.getStartTimeFrom() != null && dto.getStartTimeTo() != null
                && dto.getStartTimeFrom().isAfter(dto.getStartTimeTo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "活动开始时间范围不合法");
        }
    }

    /**
     * 判断一个活动状态是否允许出现在公开页面。
     *
     * <p>把判断单独封装成方法，是为了让“列表”和“详情”使用同一套规则，
     * 避免出现列表看不到、详情却能看到，或者反过来的权限不一致。</p>
     */
    private boolean isPublicActivityStatus(Integer status) {
        return status != null && PUBLIC_ACTIVITY_STATUSES.contains(status);
    }

    /**
     * 判断当前用户是否必须绕过公开缓存（命中缓存也不能直接用）。
     *
     * <p>公开缓存（ActivityPublicDetailVO）是所有访问者共用的数据对象，不含审核留痕。
     * 绝大多数普通访问者可以放心复用缓存，但两类人不行：</p>
     * <ol>
     *   <li><b>管理员</b>：需要审计审核过程（谁审的、何时审的、为什么拒）；</li>
     *   <li><b>活动组织者</b>：需要看到自己活动的驳回原因来修改重新提交。</li>
     * </ol>
     * <p>这两类人必须继续回查 MySQL，由 buildActivityDetail 重新组装
     * 携带审核信息的完整详情 —— 宁可多一次 SQL，也不能丢审核信息。</p>
     *
     * @param currentUser  当前登录用户（匿名访问为 null）
     * @param cachedDetail 命中的公开缓存详情（organizerId 用于判断是否组织者本人）
     * @return true=必须绕过缓存回查 MySQL；false=可以直接复用缓存
     */
    private boolean mustBypassPublicCache(UserVO currentUser, ActivityPublicDetailVO cachedDetail) {
        // 管理员：任何活动的审核信息都有权查看，一律绕过缓存
        if (isAdmin(currentUser)) {
            return true;
        }
        // 组织者：缓存里记录了 organizerId，当前用户 ID 与之相同说明他是活动组织者。
        // 未登录（currentUser 为 null）时不会走到这里，isAdmin 已处理 null 的情况；
        // 这里再判空是防止缓存数据异常（organizerId 为 null）时 equals 调用空指针。
        return currentUser != null && currentUser.getId() != null
                && currentUser.getId().equals(cachedDetail.getOrganizerId());
    }

    /**
     * 判断当前用户能否查看非公开活动。
     *
     * <p>管理员可以查看所有活动；普通组织者只能查看自己创建的活动。
     * 这里仅用于“查看”，不代表用户可以编辑、审核或下架活动，
     * 写操作仍然由各自的权限方法单独校验。</p>
     */
    private boolean canViewPrivateActivity(Activity activity, UserVO currentUser) {
        if (currentUser == null || currentUser.getId() == null) {
            return false;
        }
        return isAdmin(currentUser) || currentUser.getId().equals(activity.getOrganizerId());
    }

    /**
     * 判断当前用户是否为管理员（双来源，过渡期写法）。
     *
     * <p><b>为什么判断两次？</b><br>
     * 1. {@code UserVO.getRole()}：Redis 登录态里的旧整数字段（1=管理员），兼容历史数据<br>
     * 2. {@code SecurityContextHolder}：新 RBAC 体系构建的 ROLE_ADMIN 权限<br>
     * 两者任一命中都算管理员，保证旧 Token 和新 Token 行为一致。
     * 待旧字段彻底废弃后可只保留 SecurityContext 判断。</p>
     *
     * @param currentUser 当前登录用户
     * @return true=管理员
     */
    private boolean isAdmin(UserVO currentUser) {
        if (currentUser == null) {
            // 未登录用户一定不是管理员；同时避免直接调用 currentUser.getRole() 产生空指针。
            return false;
        }
        // 来源 1：旧字段 role >= 1（Redis Hash 快照）
        if (currentUser.getRole() != null && currentUser.getRole() >= 1) {
            return true;
        }
        // 来源 2：新 RBAC 权限（TokenAuthenticationFilter 第 9 步写入的 ROLE_ADMIN）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    /**
     * 获取当前登录用户，未登录直接拒绝。
     *
     * <p>虽然 SecurityConfig 的 authenticated() 已拦截未登录请求，
     * 但这里仍做防御性校验（如单元测试直接调用 Service 时没有过滤器参与）。</p>
     *
     * @return 当前登录用户
     * @throws BusinessException 未登录
     */
    private UserVO requireCurrentUser() {
        UserVO currentUser = UserHolder.getUserVO();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return currentUser;
    }

    /**
     * 校验活动分类存在。
     *
     * <p>创建/编辑时活动必须归属一个真实存在的分类，防止脏数据
     * （分类删除后活动仍挂着不存在的分类 ID）。</p>
     *
     * @param categoryId 分类 ID
     * @throws BusinessException 分类不存在
     */
    private void requireCategory(Long categoryId) {
        if (categoryId == null || categoryId <= 0) {
            throw new BusinessException(ResultCode.ACTIVITY_CATEGORY_NOT_FOUND);
        }
        ActivityCategory category = activityCategoryService.getById(categoryId);
        if (category == null) {
            throw new BusinessException(ResultCode.ACTIVITY_CATEGORY_NOT_FOUND);
        }
    }

    /**
     * 校验报名和活动时间的先后关系。
     *
     * <p>合法顺序：报名开始 &lt; 报名结束 ≤ 活动开始 &lt; 活动结束<br>
     * 即：先开放报名，报名截止不晚于活动开始（不能活动开始了还能报名），活动开始早于结束。</p>
     *
     * @param startTime        活动开始时间
     * @param endTime          活动结束时间
     * @param enrollStartTime  报名开始时间
     * @param enrollEndTime    报名截止时间
     * @throws BusinessException 任一时间为空或先后关系不合法
     */
    private void validateTime(LocalDateTime startTime, LocalDateTime endTime,
                              LocalDateTime enrollStartTime, LocalDateTime enrollEndTime) {
        if (startTime == null || endTime == null || enrollStartTime == null || enrollEndTime == null
                || !enrollStartTime.isBefore(enrollEndTime)
                || enrollEndTime.isAfter(startTime)
                || !startTime.isBefore(endTime)) {
            throw new BusinessException(ResultCode.ACTIVITY_TIME_INVALID);
        }

        // 创建或编辑活动时，活动开始时间不能已经过去。
        // 如果不加这个判断，用户可以创建一条“刚保存就已经结束”的活动，
        // 定时任务下一分钟就会把它连续推进到 FINISHED，前端看起来会非常奇怪。
        if (!startTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.ACTIVITY_TIME_INVALID, "活动开始时间必须晚于当前时间");
        }
    }

    /**
     * 可选文本校验：传了值但内容是空白 → 拒绝。
     *
     * <p>与 DTO 的 @NotBlank 区别：@NotBlank 要求必填（null 就报错），
     * 这里用于部分更新场景 —— 字段可以为 null（不修改），但一旦传了就不能是空白。</p>
     *
     * @param value   字段值（可能为 null）
     * @param message 校验失败时的提示信息
     * @throws BusinessException 值为空白
     */
    private void validateOptionalText(String value, String message) {
        if (value != null && !StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
    }

    /**
     * 取默认值：value 为 null 时返回 defaultValue（部分更新时用数据库原值补齐）。
     *
     * @param value        待判断的值
     * @param defaultValue 默认值
     * @return 非 null 返回 value，否则返回 defaultValue
     */
    private <T> T valueOrDefault(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }

    /**
     * 把 Activity 实体转换成列表项 VO，从 Map 里取分类名和组织者昵称填充。
     *
     * <p>{@code getActivityPage} 和 {@code getMyActivities} 共用本方法，保证转换逻辑一致。
     * {@code Map.get} 找不到时返回 null（分类或用户被删除的边界情况），VO 对应字段保持 null，
     * 前端展示时做容错即可。</p>
     *
     * @param activity              活动实体
     * @param categoryNameMap       分类 ID → 分类名（批量查询后构建）
     * @param organizerNicknameMap  用户 ID → 昵称（批量查询后构建）
     * @return 列表项 VO
     */
    private ActivityListItemVO convertToActivityVO(Activity activity,
                                                   Map<Long, String> categoryNameMap,
                                                   Map<Long, String> organizerNicknameMap) {
        ActivityListItemVO vo = new ActivityListItemVO();
        vo.setId(activity.getId());
        vo.setTitle(activity.getTitle());
        vo.setCoverImage(activity.getCoverImage());
        vo.setCategoryId(activity.getCategoryId());
        vo.setCategoryName(categoryNameMap.get(activity.getCategoryId()));
        vo.setLocation(activity.getLocation());
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setCurrentCount(activity.getCurrentCount());
        vo.setMaxCount(activity.getMaxCount());
        vo.setStatus(activity.getStatus());
        vo.setOrganizerId(activity.getOrganizerId());
        vo.setOrganizerNickname(organizerNicknameMap.get(activity.getOrganizerId()));
        vo.setCreateTime(activity.getCreateTime());
        return vo;
    }

    /**
     * 批量查分类名，构建 categoryId → 分类名 的 Map。
     *
     * <p>从活动列表中收集所有不重复的 categoryId，用 {@code listByIds} 一次性查出，
     * 避免 N+1 查询。空集合时直接返回空 Map，不发 SQL。</p>
     *
     * @param activities 活动列表
     * @return 分类 ID → 分类名 Map
     */
    private Map<Long, String> loadCategoryNameMap(List<Activity> activities) {
        Map<Long, String> categoryNameMap = new HashMap<>();
        Set<Long> categoryIds = activities.stream()
                .map(Activity::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (categoryIds.isEmpty()) {
            return categoryNameMap;
        }
        for (ActivityCategory category : activityCategoryService.listByIds(categoryIds)) {
            // HashMap 允许 value 为 null，兼容异常/脏数据，不让列表查询整体失败。
            categoryNameMap.put(category.getId(), category.getName());
        }
        return categoryNameMap;
    }

    /**
     * 批量查组织者昵称，构建 userId → 昵称 的 Map。
     *
     * <p>从活动列表中收集所有不重复的 organizerId，用 {@code selectByIds} 一次性查出，
     * 避免 N+1 查询。空集合时直接返回空 Map，不发 SQL。</p>
     *
     * @param activities 活动列表
     * @return 用户 ID → 昵称 Map
     */
    private Map<Long, String> loadOrganizerNicknameMap(List<Activity> activities) {
        Map<Long, String> organizerNicknameMap = new HashMap<>();
        Set<Long> organizerIds = activities.stream()
                .map(Activity::getOrganizerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (organizerIds.isEmpty()) {
            return organizerNicknameMap;
        }
        for (User user : userMapper.selectByIds(organizerIds)) {
            // 用户昵称允许为空，使用 put 避免 Collectors.toMap 对 null value 抛异常。
            organizerNicknameMap.put(user.getId(), user.getNickname());
        }
        return organizerNicknameMap;
    }
}
