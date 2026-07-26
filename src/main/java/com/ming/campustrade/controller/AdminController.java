package com.ming.campustrade.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.RequireRole;
import com.ming.campustrade.service.OrderService;
import com.ming.campustrade.service.ProductService;
import com.ming.campustrade.service.UserService;
import com.ming.campustrade.vo.OrderVO;
import com.ming.campustrade.vo.ProductVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;

/**
 * 管理员后台控制器 —— 处理商品审核、订单管理、用户封禁等后台 HTTP 请求。
 *
 * <h2>权限说明</h2>
 * <p>类上标注 {@code @RequireRole(1)}，表示本控制器下<b>所有接口都需要管理员权限</b>
 * （role >= 1）。普通用户访问任意接口都会被 RoleInterceptor 拦截并返回 403。
 * 这样无需在每个方法上重复标注，统一管理后台接口的权限。</p>
 *
 * <h2>接口一览</h2>
 * <ul>
 *   <li>商品审核：{@code POST /admin/product/{id}/review}</li>
 *   <li>商品列表（含待审核）：{@code GET /admin/product/list}</li>
 *   <li>订单列表（全部）：{@code GET /admin/order/list}</li>
 *   <li>封禁用户：{@code POST /admin/user/{id}/ban}</li>
 *   <li>解封用户：{@code POST /admin/user/{id}/unban}</li>
 * </ul>
 *
 * @author ming
 */
@Slf4j
@Tag(name = "管理员后台", description = "商品审核、订单管理、用户封禁等后台管理操作（仅管理员）")
@RestController
@RequestMapping("/admin")
@RequireRole(1) // 整个控制器都需要管理员权限
@Validated // 启用方法参数（@RequestParam/@PathVariable）上的约束校验（如 @Min/@Max）
public class AdminController {

    private final ProductService productService;
    private final OrderService orderService;
    private final UserService userService;

    /**
     * 构造器注入：Spring 启动时自动把三个 Service 的实现类实例传进来。
     */
    public AdminController(ProductService productService, OrderService orderService, UserService userService) {
        this.productService = productService;
        this.orderService = orderService;
        this.userService = userService;
    }

    // ==================== 商品审核 ====================

    /**
     * 管理员查看商品详情（任意状态，含审核备注）。
     *
     * <p>管理员需查看任何商品（包括待审核/已驳回/下架）以进行审核和处理。</p>
     *
     * @param id 商品ID（路径变量）
     * @return 商品详细信息
     */
    @Operation(summary = "商品详情（管理员）", description = "管理员查看任意状态的商品详情（含审核备注）")
    @GetMapping("/product/{id}")
    public Result<ProductVO> getProductById(@Parameter(description = "商品ID") @PathVariable Long id) {
        log.info("管理员查看商品详情：productId={}", id);
        return Result.success(productService.getProductByIdForAdmin(id));
    }

    /**
     * 审核商品（通过上架 / 不通过下架）。
     *
     * <p>{@code @RequestParam approved} 表示审核结果：true=通过（上架），false=不通过（下架）。
     * 只有处于"待审核"状态的商品才能被审核，其他状态会返回错误。</p>
     *
     * <p>{@code remark} 为审核备注：驳回时填写驳回原因（卖家可见），通过时可不填。</p>
     *
     * @param id       商品ID（路径变量）
     * @param approved 审核结果：true=通过上架，false=不通过下架
     * @param remark   审核备注（驳回原因，可选）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "审核商品", description = "管理员审核待审核商品，通过则上架，不通过则下架，驳回时可填写原因")
    @PostMapping("/product/{id}/review")
    public Result<Void> reviewProduct(@Parameter(description = "商品ID") @PathVariable Long id,
                                      @Parameter(description = "审核结果：true通过上架 false不通过下架") @RequestParam Boolean approved,
                                      @Parameter(description = "审核备注（驳回原因，可选）") @RequestParam(required = false) String remark) {
        log.info("管理员审核商品：productId={}, approved={}", id, approved);
        productService.reviewProduct(id, approved, remark);
        return Result.success();
    }

    /**
     * 管理员查询商品列表（含待审核商品，可按状态筛选）。
     *
     * <p>与前台商品列表的区别：这里可以查到所有状态的商品（包括待审核、已下架），
     * 方便管理员审核和管理。status 不传时查全部状态。</p>
     *
     * @param status   商品状态筛选（可选：0下架 1在售 2锁定 3已售 4待审核）
     * @param pageNo   页码，从1开始（默认1）
     * @param pageSize 每页条数（默认10）
     * @return 分页的商品列表
     */
    @Operation(summary = "商品列表（管理员）", description = "分页查询所有状态的商品，支持按状态筛选（含待审核）")
    @GetMapping("/product/list")
    public Result<IPage<ProductVO>> listProducts(@Parameter(description = "状态筛选：0下架 1在售 2锁定 3已售 4待审核 5已驳回，不传查全部") @RequestParam(required = false) Integer status,
                                                 @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNo,
                                                 @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        log.info("管理员查询商品列表：status={}, pageNo={}, pageSize={}", status, pageNo, pageSize);
        return Result.success(productService.listProductsForAdmin(status, pageNo, pageSize));
    }

    // ==================== 订单管理 ====================

    /**
     * 管理员查询平台全部订单（可按状态筛选）。
     *
     * <p>与"我买到的/我卖出的"不同，管理员可以看到平台所有订单，
     * 便于处理交易纠纷、监控异常交易等。status 不传时查全部状态。</p>
     *
     * @param status   订单状态筛选（可选：0待确认 1已确认 2已取消）
     * @param pageNo   页码，从1开始（默认1）
     * @param pageSize 每页条数（默认10）
     * @return 分页的订单列表
     */
    @Operation(summary = "订单列表（管理员）", description = "分页查询平台全部订单，支持按状态筛选")
    @GetMapping("/order/list")
    public Result<IPage<OrderVO>> listOrders(@Parameter(description = "状态筛选：0待确认 1已确认 2已取消，不传查全部") @RequestParam(required = false) Integer status,
                                             @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNo,
                                             @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        log.info("管理员查询订单列表：status={}, pageNo={}, pageSize={}", status, pageNo, pageSize);
        return Result.success(orderService.listOrdersForAdmin(status, pageNo, pageSize));
    }

    // ==================== 用户封禁 ====================

    /**
     * 封禁用户（仅管理员）。
     *
     * <p>封禁后该用户无法登录（登录时会校验 status）。不能封禁管理员账号。</p>
     *
     * @param id 用户ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "封禁用户", description = "封禁指定用户，封禁后该用户无法登录（不能封禁管理员）")
    @PostMapping("/user/{id}/ban")
    public Result<Void> banUser(@Parameter(description = "用户ID") @PathVariable Long id) {
        log.info("管理员封禁用户：targetUserId={}", id);
        userService.banUser(id);
        return Result.success();
    }

    /**
     * 解封用户（仅管理员）。
     *
     * <p>解封后该用户恢复正常，可以登录使用。</p>
     *
     * @param id 用户ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "解封用户", description = "解封指定用户，恢复其正常登录使用")
    @PostMapping("/user/{id}/unban")
    public Result<Void> unbanUser(@Parameter(description = "用户ID") @PathVariable Long id) {
        log.info("管理员解封用户：targetUserId={}", id);
        userService.unbanUser(id);
        return Result.success();
    }
}
