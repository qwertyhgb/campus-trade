package com.ming.campustrade.controller;

import java.util.List;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.dto.ActivityCreateDTO;
import com.ming.campustrade.dto.ActivityQueryDTO;
import com.ming.campustrade.dto.ActivityReviewDTO;
import com.ming.campustrade.dto.ActivityUpdateDTO;
import com.ming.campustrade.service.ActivityService;
import com.ming.campustrade.vo.ActivityDetailVO;
import com.ming.campustrade.vo.ActivityListItemVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 活动控制器：把活动 Service 的业务能力暴露为 HTTP 接口。
 *
 * <p>控制器只负责四件事：</p>
 * <ol>
 *     <li>接收 HTTP 参数并通过 {@code @Valid} 做基础格式校验；</li>
 *     <li>通过 {@code @PreAuthorize} 做接口级角色拦截；</li>
 *     <li>调用 Service 完成真正的业务规则校验和数据库操作；</li>
 *     <li>把结果统一包装成 {@link Result} 返回。</li>
 * </ol>
 *
 * <p>注意：角色权限只是第一道门，Service 仍会再次校验登录身份、活动归属和状态，
 * 防止其他代码绕过 Controller 直接调用 Service 时出现越权。</p>
 *
 * @author ming
 */
@Slf4j
@Tag(name = "活动管理", description = "活动的创建、编辑、审核、下架、查询和筛选")
@RestController
@RequestMapping("/activity")
@Validated
public class ActivityController {

    /**
     * 活动业务 Service。
     *
     * <p>这里按本阶段要求使用 {@code @Resource} 注入。Controller 不直接操作 Mapper，
     * 所有业务规则统一交给 ActivityService。</p>
     */
    @Resource
    private ActivityService activityService;

    /**
     * 创建活动。
     *
     * <p>创建成功后返回数据库生成的活动 ID，前端可以使用该 ID 继续编辑或提交审核。</p>
     */
    @Operation(summary = "创建活动", description = "组织者或管理员创建活动，活动初始状态为草稿，并返回活动ID")
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @PostMapping("/create")
    public Result<Long> create(@RequestBody @Valid ActivityCreateDTO dto) {
        log.info("创建活动请求：title={}, categoryId={}", dto.getTitle(), dto.getCategoryId());
        Long activityId = activityService.createActivity(dto);
        log.info("创建活动成功：activityId={}", activityId);
        return Result.success(activityId);
    }

    /**
     * 编辑活动。
     *
     * <p>ActivityUpdateDTO 的业务字段是可选的，Service 会执行部分更新；
     * 只有组织者本人或管理员可以编辑草稿/审核拒绝状态的活动。</p>
     */
    @Operation(summary = "编辑活动", description = "组织者或管理员编辑活动，只有草稿/审核拒绝状态允许修改")
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody @Valid ActivityUpdateDTO dto) {
        log.info("编辑活动请求：activityId={}", dto.getId());
        activityService.updateActivity(dto);
        log.info("编辑活动成功：activityId={}", dto.getId());
        return Result.success();
    }

    /**
     * 删除活动。
     *
     * <p>Service 使用 {@code @TableLogic} 执行逻辑删除，不会物理删除数据库记录。</p>
     */
    @Operation(summary = "删除活动", description = "组织者或管理员删除活动，实际执行逻辑删除")
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(description = "活动ID")
            @PathVariable @Min(value = 1, message = "活动ID必须大于0") Long id) {
        log.info("删除活动请求：activityId={}", id);
        activityService.deleteActivity(id);
        log.info("删除活动成功：activityId={}", id);
        return Result.success();
    }

    /**
     * 组织者提交审核。
     */
    @Operation(summary = "提交活动审核", description = "组织者提交草稿或审核拒绝的活动，等待管理员审核")
    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @PostMapping("/{id}/submit-review")
    public Result<Void> submitReview(
            @Parameter(description = "活动ID")
            @PathVariable @Min(value = 1, message = "活动ID必须大于0") Long id) {
        log.info("提交活动审核请求：activityId={}", id);
        activityService.submitReview(id);
        log.info("提交活动审核成功：activityId={}", id);
        return Result.success();
    }

    /**
     * 审核活动。
     *
     * <p>审核员或管理员可以执行。是否通过由 DTO 的 pass 字段决定；
     * 驳回时必须填写 rejectReason，具体跨字段校验由 Service 完成。</p>
     */
    @Operation(summary = "审核活动", description = "审核员或管理员审核待审核活动，通过后进入报名中，拒绝时必须填写原因")
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMIN')")
    @PostMapping("/review")
    public Result<Void> review(@RequestBody @Valid ActivityReviewDTO dto) {
        log.info("审核活动请求：activityId={}, pass={}", dto.getId(), dto.getPass());
        activityService.reviewActivity(dto);
        log.info("审核活动成功：activityId={}", dto.getId());
        return Result.success();
    }

    /**
     * 管理员下架活动。
     */
    @Operation(summary = "下架活动", description = "管理员下架非终态活动，下架后活动不可继续报名")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/off-shelf")
    public Result<Void> offShelf(
            @Parameter(description = "活动ID")
            @PathVariable @Min(value = 1, message = "活动ID必须大于0") Long id) {
        log.info("下架活动请求：activityId={}", id);
        activityService.offShelf(id);
        log.info("下架活动成功：activityId={}", id);
        return Result.success();
    }

    /**
     * 活动分页列表。
     *
     * <p>GET 请求不使用 {@code @RequestBody}。Spring MVC 会把 URL 查询参数，
     * 例如 {@code ?pageNo=1&pageSize=10&keyword=讲座}，自动绑定到 ActivityQueryDTO；
     * {@code @Valid} 会触发页码和每页条数等 DTO 校验。</p>
     */
    @Operation(summary = "活动列表", description = "公开分页查询活动，支持关键词、分类、状态和开始时间范围筛选")
    @GetMapping("/list")
    public Result<IPage<ActivityListItemVO>> list(@Valid ActivityQueryDTO dto) {
        log.info("查询活动列表：pageNo={}, pageSize={}, keyword={}, categoryId={}, status={}",
                dto.getPageNo(), dto.getPageSize(), dto.getKeyword(), dto.getCategoryId(), dto.getStatus());
        return Result.success(activityService.getActivityPage(dto));
    }

    /**
     * 活动详情，公开访问。
     */
    @Operation(summary = "活动详情", description = "公开查询活动详情，包含分类名、组织者昵称、审核信息和候补人数")
    @GetMapping("/{id}")
    public Result<ActivityDetailVO> detail(
            @Parameter(description = "活动ID")
            @PathVariable @Min(value = 1, message = "活动ID必须大于0") Long id) {
        log.info("查询活动详情：activityId={}", id);
        return Result.success(activityService.getActivityDetail(id));
    }

    /**
     * 当前组织者查看自己的活动。
     *
     * <p>当前用户 ID 由 Service 从 UserHolder 获取，不从 URL 或请求参数接收，
     * 防止用户通过修改 userId 查看他人的活动。</p>
     */
    @Operation(summary = "我的活动", description = "登录用户查看自己创建的全部活动，按创建时间倒序")
    @GetMapping("/my")
    public Result<List<ActivityListItemVO>> my() {
        log.info("查询我的活动");
        return Result.success(activityService.getMyActivities());
    }
}
