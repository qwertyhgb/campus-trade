package com.ming.campustrade.controller;

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
import com.ming.campustrade.dto.OrderPlaceDTO;
import com.ming.campustrade.service.OrderService;
import com.ming.campustrade.vo.OrderVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Tag(name = "订单管理", description = "订单的创建、确认、取消、查询等操作")
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "下单", description = "买家购买商品，创建订单并锁定商品库存")
    @PostMapping("/place")
    public Result<Void> placeOrder(@RequestBody @Valid OrderPlaceDTO orderPlaceDTO) {
        log.info("下单：productId={}", orderPlaceDTO.getProductId());
        orderService.placeOrder(orderPlaceDTO);
        log.info("下单成功：productId={}", orderPlaceDTO.getProductId());
        return Result.success();
    }

    @Operation(summary = "确认订单", description = "卖家确认订单，订单状态变为已完成，商品标记为已售出")
    @PutMapping("/{id}/confirm")
    public Result<Void> confirmOrder(@Parameter(description = "订单ID") @PathVariable Long id) {
        log.info("确认订单：orderId={}", id);
        orderService.confirmOrder(id);
        log.info("确认订单成功：orderId={}", id);
        return Result.success();
    }

    @Operation(summary = "取消订单", description = "买家或卖家取消待确认状态的订单，释放锁定商品")
    @PutMapping("/{id}/cancel")
    public Result<Void> cancelOrder(@Parameter(description = "订单ID") @PathVariable Long id) {
        log.info("取消订单：orderId={}", id);
        orderService.cancelOrder(id);
        log.info("取消订单成功：orderId={}", id);
        return Result.success();
    }

    @Operation(summary = "查询订单详情", description = "根据订单ID获取订单详细信息（仅买家或卖家可查看）")
    @GetMapping("/{id}")
    public Result<OrderVO> getOrderById(@Parameter(description = "订单ID") @PathVariable Long id) {
        log.info("查询订单详情：orderId={}", id);
        return Result.success(orderService.getOrderById(id));
    }

    @Operation(summary = "我买到的订单", description = "分页查询当前登录买家购买的订单列表，按时间倒序")
    @GetMapping("/buy")
    public Result<IPage<OrderVO>> getBuyOrder(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer pageNo,
                                                @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("查询我买到的订单：pageNo={}, pageSize={}", pageNo, pageSize);
        return Result.success(orderService.getBuyOrder(pageNo, pageSize));
    }

    @Operation(summary = "我卖出的订单", description = "分页查询当前登录卖家卖出的订单列表，按时间倒序")
    @GetMapping("/sell")
    public Result<IPage<OrderVO>> getSellOrder(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer pageNo,
                                                 @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("查询我卖出的订单：pageNo={}, pageSize={}", pageNo, pageSize);
        return Result.success(orderService.getSellOrder(pageNo, pageSize));
    }
}
