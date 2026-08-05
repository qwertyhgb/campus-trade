package com.ming.campustrade.controller;

import java.util.List;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.dto.ActivityCategoryDTO;
import com.ming.campustrade.service.ActivityCategoryService;
import com.ming.campustrade.vo.ActivityCategoryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动分类管理控制器 —— 处理活动分类的增删查等 HTTP 请求。
 *
 * <h2>权限约定</h2>
 * <ul>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")}：增删改操作仅管理员可执行（分类是全局数据）</li>
 *   <li>查询操作由 {@code SecurityConfig} 配置为公开接口（发布活动时前端需要拉取分类下拉列表）</li>
 * </ul>
 *
 * @author ming
 */
@Slf4j
@Tag(name = "活动分类管理", description = "活动分类的增删查等操作")
@RestController
@RequestMapping("/activity-category")
public class ActivityCategoryController {

    private final ActivityCategoryService activityCategoryService;

    /**
     * 构造器注入：Spring 启动时自动把 ActivityCategoryService 的实现类实例传进来。
     */
    public ActivityCategoryController(ActivityCategoryService activityCategoryService) {
        this.activityCategoryService = activityCategoryService;
    }

    /**
     * 添加活动分类（仅管理员）。
     *
     * @param dto 新分类信息（名称、排序值）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "添加活动分类", description = "新增活动分类（仅管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/add")
    public Result<Void> add(@RequestBody @Valid ActivityCategoryDTO dto) {
        log.info("添加活动分类：name={}", dto.getName());
        activityCategoryService.addCategory(dto);
        return Result.success();
    }

    /**
     * 修改活动分类（仅管理员）。
     *
     * @param dto 要更新的分类信息（必须包含 id 字段）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "修改活动分类", description = "修改活动分类信息（仅管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/update")
    public Result<Void> update(@RequestBody @Valid ActivityCategoryDTO dto) {
        log.info("修改活动分类：categoryId={}, name={}", dto.getId(), dto.getName());
        activityCategoryService.updateCategory(dto);
        return Result.success();
    }

    /**
     * 删除活动分类（仅管理员）。
     *
     * <p>若该分类下还有活动，Service 层会拒绝删除并返回友好提示。</p>
     *
     * @param id 分类 ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "删除活动分类", description = "删除活动分类（仅管理员），分类下有活动时拒绝删除")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "分类ID") @PathVariable Long id) {
        log.info("删除活动分类：categoryId={}", id);
        activityCategoryService.deleteCategory(id);
        return Result.success();
    }

    /**
     * 活动分类列表（公开接口，无需登录）。
     *
     * <p>SecurityConfig 将此接口配置为公开接口。
     * 为什么公开？因为前端在「发布活动」页面需要拉取分类下拉选项，
     * 用户可能还没登录就想先看看有哪些分类。</p>
     *
     * @return 所有活动分类的列表（按 sort 升序）
     */
    @Operation(summary = "活动分类列表", description = "获取所有活动分类列表（公开接口），发布活动时前端用来拉取分类选项")
    @GetMapping("/list")
    public Result<List<ActivityCategoryVO>> list() {
        log.info("查询活动分类列表");
        List<ActivityCategoryVO> list = activityCategoryService.getCategoryList();
        log.info("查询活动分类列表成功，共 {} 条", list.size());
        return Result.success(list);
    }
}
