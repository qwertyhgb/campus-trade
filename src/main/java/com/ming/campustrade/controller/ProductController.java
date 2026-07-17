package com.ming.campustrade.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.PublicApi;
import com.ming.campustrade.dto.ProductPublishDTO;
import com.ming.campustrade.dto.ProductQueryDTO;
import com.ming.campustrade.dto.ProductUpdateDTO;
import com.ming.campustrade.service.ProductService;
import com.ming.campustrade.vo.ProductVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag(name = "商品管理", description = "商品的发布、编辑、删除、查询、上下架等操作")
@RestController
@RequestMapping("/product")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "发布商品", description = "卖家发布新商品，需要提供标题、价格、分类等信息")
    @PostMapping("/publish")
    public Result<Void> publish(@RequestBody @Valid ProductPublishDTO productPublishDTO) {
        log.info("发布商品：title={}, price={}", productPublishDTO.getTitle(), productPublishDTO.getPrice());
        productService.publishProduct(productPublishDTO);
        log.info("发布商品成功：title={}", productPublishDTO.getTitle());
        return Result.success();
    }

    @Operation(summary = "编辑商品", description = "卖家修改自己已发布的商品信息")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "商品ID") @PathVariable Long id,
                                @RequestBody @Valid ProductUpdateDTO productUpdateDTO) {
        log.info("编辑商品：productId={}, title={}", id, productUpdateDTO.getTitle());
        productService.updateProduct(id, productUpdateDTO);
        log.info("编辑商品成功：productId={}", id);
        return Result.success();
    }

    @Operation(summary = "删除商品", description = "卖家删除自己的商品（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "商品ID") @PathVariable Long id) {
        log.info("删除商品：productId={}", id);
        productService.deleteProduct(id);
        log.info("删除商品成功：productId={}", id);
        return Result.success();
    }

    @Operation(summary = "查询商品详情", description = "根据商品ID获取商品详细信息（公开接口）")
    @PublicApi
    @GetMapping("/{id}")
    public Result<ProductVO> getById(@Parameter(description = "商品ID") @PathVariable Long id) {
        log.info("查询商品详情：productId={}", id);
        return Result.success(productService.getProductById(id));
    }

    @Operation(summary = "商品列表查询", description = "根据条件分页查询在售商品列表（公开接口），支持按分类、关键词等筛选")
    @PublicApi
    @GetMapping("/list")
    public Result<IPage<ProductVO>> list(ProductQueryDTO productQueryDTO) {
        log.info("商品列表查询：keyword={}, categoryId={}, pageNo={}, pageSize={}",
                productQueryDTO.getKeyword(), productQueryDTO.getCategoryId(),
                productQueryDTO.getPageNo(), productQueryDTO.getPageSize());
        return Result.success(productService.listProducts(productQueryDTO));
    }

    @Operation(summary = "修改商品状态", description = "卖家上下架商品，status=0 下架，status=1 上架")
    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@Parameter(description = "商品ID") @PathVariable Long id,
                                      @Parameter(description = "状态：0下架 1上架") @RequestParam Integer status) {
        log.info("修改商品状态：productId={}, status={}", id, status);
        productService.updateStatus(id, status);
        log.info("修改商品状态成功：productId={}, status={}", id, status);
        return Result.success();
    }

    @Operation(summary = "我的商品列表", description = "查看当前登录卖家发布的全部商品（含下架、在售、已售），分页返回")
    @GetMapping("/my")
    public Result<IPage<ProductVO>> myProducts(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer pageNo,
                                                @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("查询我的商品列表：pageNo={}, pageSize={}", pageNo, pageSize);
        return Result.success(productService.getMyProducts(pageNo, pageSize));
    }
}
