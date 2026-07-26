package com.ming.campustrade.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.service.FavoriteService;
import com.ming.campustrade.vo.FavoriteVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;

@Tag(name = "收藏管理", description = "商品的收藏、取消收藏、收藏状态查询、我的收藏列表等操作")
@RestController
@RequestMapping("/favorite")
@Validated // 启用方法参数（@RequestParam/@PathVariable）上的约束校验（如 @Min/@Max）
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @Operation(summary = "添加收藏", description = "当前登录用户收藏指定商品，重复收藏幂等处理不报错")
    @PostMapping("/{productId}")
    public Result<Void> addFavorite(@Parameter(description = "商品ID") @PathVariable Long productId) {
        favoriteService.addFavorite(productId);
        return Result.success();
    }

    @Operation(summary = "取消收藏", description = "当前登录用户取消收藏指定商品（物理删除收藏记录）")
    @DeleteMapping("/{productId}")
    public Result<Void> removeFavorite(@Parameter(description = "商品ID") @PathVariable Long productId) {
        favoriteService.removeFavorite(productId);
        return Result.success();
    }

    @Operation(summary = "查询收藏状态", description = "判断当前登录用户是否已收藏指定商品，前端据此展示收藏按钮状态")
    @GetMapping("/{productId}/status")
    public Result<Boolean> isFavorited(@Parameter(description = "商品ID") @PathVariable Long productId) {
        return Result.success(favoriteService.isFavorited(productId));
    }

    @Operation(summary = "我的收藏列表", description = "分页查询当前登录用户的收藏商品列表，按收藏时间倒序")
    @GetMapping("/my")
    public Result<IPage<FavoriteVO>> getMyFavorites(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNo,
                                                     @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        return Result.success(favoriteService.getMyFavorites(pageNo, pageSize));
    }
}
