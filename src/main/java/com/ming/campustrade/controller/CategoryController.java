package com.ming.campustrade.controller;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.PublicApi;
import com.ming.campustrade.common.annotation.RequireRole;
import com.ming.campustrade.dto.CategoryAddDTO;
import com.ming.campustrade.dto.CategoryUpdateDTO;
import com.ming.campustrade.service.CategoryService;
import com.ming.campustrade.vo.CategoryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "分类管理", description = "商品分类的增删改查等操作")
@RestController
@RequestMapping("/category")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Operation(summary = "添加分类", description = "新增商品分类（仅管理员）")
    @RequireRole(1)
    @PostMapping("/add")
    public Result<Void> add(@RequestBody @Valid CategoryAddDTO dto) {
        log.info("添加分类：name={}", dto.getName());
        categoryService.addCategory(dto);
        log.info("添加分类成功：name={}", dto.getName());
        return Result.success();
    }

    @Operation(summary = "修改分类", description = "修改商品分类信息（仅管理员）")
    @RequireRole(1)
    @PutMapping("/update")
    public Result<Void> update(@RequestBody @Valid CategoryUpdateDTO dto) {
        log.info("修改分类：categoryId={}, name={}", dto.getId(), dto.getName());
        categoryService.updateCategory(dto);
        log.info("修改分类成功：categoryId={}", dto.getId());
        return Result.success();
    }

    @Operation(summary = "删除分类", description = "删除商品分类（仅管理员）")
    @RequireRole(1)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "分类ID") @PathVariable Long id) {
        log.info("删除分类：categoryId={}", id);
        categoryService.deleteCategory(id);
        log.info("删除分类成功：categoryId={}", id);
        return Result.success();
    }

    @Operation(summary = "分类列表", description = "获取所有商品分类列表（公开接口），发布商品时前端用来拉取分类选项")
    @PublicApi
    @GetMapping("/list")
    public Result<List<CategoryVO>> list() {
        log.info("查询分类列表");
        List<CategoryVO> list = categoryService.getCategoryList();
        log.info("查询分类列表成功，共 {} 条", list.size());
        return Result.success(list);
    }

    @Operation(summary = "查询分类详情", description = "根据分类ID获取分类详细信息（公开接口）")
    @PublicApi
    @GetMapping("/{id}")
    public Result<CategoryVO> getById(@Parameter(description = "分类ID") @PathVariable Long id) {
        log.info("查询分类详情：categoryId={}", id);
        return Result.success(categoryService.getCategory(id));
    }
}