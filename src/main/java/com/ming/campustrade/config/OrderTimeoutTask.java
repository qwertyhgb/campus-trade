package com.ming.campustrade.config;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ming.campustrade.common.constant.OrderStatus;
import com.ming.campustrade.common.constant.ProductStatus;
import com.ming.campustrade.entity.Order;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.mapper.OrderMapper;
import com.ming.campustrade.mapper.ProductMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 订单超时自动取消定时任务
 *
 * <p><b>业务背景：</b><br>
 * 买家下单后，商品会被锁定（status=LOCKED），其他用户无法购买。
 * 如果买家下单后长时间不确认/付款，商品会一直被锁着，卖家无法卖给其他人。
 * 为了释放这些"占着茅坑不拉屎"的商品，需要一个定时任务：
 * 每隔一段时间扫描超时未确认的订单，自动取消并释放商品。</p>
 *
 * <p><b>执行频率：</b>每 60 秒执行一次（fixedRate = 60000 毫秒）</p>
 *
 * <p><b>超时阈值：</b>30 分钟（TIMEOUT_MINUTES = 30）
 * 即下单后 30 分钟内未确认的订单会被自动取消。</p>
 *
 * <p><b>为什么用定时任务而不是延迟队列？</b><br>
 * 延迟队列（如 RabbitMQ 死信队列、Redis ZSet）精度更高、实时性更好，
 * 但引入了额外的中间件依赖，运维复杂度上升。校园平台订单量小，
 * 对超时精度要求不高（差个几十秒无所谓），用 Spring 自带的 @Scheduled
 * 定时轮询最简单，无需额外依赖，完全够用。</p>
 *
 * @author ming
 */
@Component
@Slf4j
public class OrderTimeoutTask {

    /**
     * 订单超时阈值（单位：分钟）。
     * 下单后超过这个时间仍未确认，订单将被自动取消。
     */
    private final static int TIMEOUT_MINUTES = 30;

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;

    /**
     * 构造器注入：Spring 启动时自动把 OrderMapper 和 ProductMapper 的实例传进来。
     */
    public OrderTimeoutTask(OrderMapper orderMapper, ProductMapper productMapper) {
        this.orderMapper = orderMapper;
        this.productMapper = productMapper;
    }

    /**
     * 扫描并取消超时未确认的订单，同时释放被锁定的商品
     *
     * <p><b>执行流程（4 步）：</b></p>
     * <ol>
     *   <li>计算超时时间点（当前时间 - 30 分钟）</li>
     *   <li>查询所有"待确认且创建时间早于超时点"的订单</li>
     *   <li>逐个取消订单（状态 → CANCELED）</li>
     *   <li>释放对应商品（状态 LOCKED → ON_SALE，让其他人可以购买）</li>
     * </ol>
     *
     * <p><b>为什么不用 @Transactional 包整个方法？</b><br>
     * 如果把所有订单放在一个大事务里，其中任何一笔失败（如商品已被删除）
     * 会导致整批回滚——前 98 笔已经成功取消的订单也被撤销，
     * 而且下次扫描还会查到它们，反复失败反复回滚，形成“死循环”。
     * 所以这里不用大事务，而是每笔订单独立处理 + try-catch 容错，
     * 一笔失败不影响其他订单的正常取消。</p>
     *
     * <p><b>如何防止竞态条件？</b><br>
     * 查出超时订单后、取消前，卖家可能刚好确认了该订单。
     * 如果用 updateById 无条件更新，会把“已确认”的订单也强行取消。
     * 解决方案：用条件更新（UPDATE ... WHERE status = PENDING），
     * 只有订单仍然是“待确认”状态时才取消，已被确认的不会被误伤。
     * 这与 OrderServiceImpl.placeOrder 中“条件更新防超卖”是同一个思路。</p>
     *
     * <p><b>为什么用 fixedRate 而不是 cron？</b><br>
     * fixedRate = 60000 表示"每隔 60 秒执行一次"，简单直观。
     * cron 表达式更适合"每天凌晨 2 点"这种固定时刻的场景。
     * 订单超时取消只需要"每隔一会儿扫一次"，用 fixedRate 更合适。</p>
     */
    @Scheduled(fixedRate = 60000)
    public void cancelTimeoutOrders() {
        // ===== 第 1 步：计算超时时间点 =====
        // 例如当前时间 14:30，超时点就是 14:00，创建时间早于 14:00 的待确认订单即为超时
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);

        // ===== 第 2 步：查询超时未确认的订单 =====
        // 条件：status = PENDING（待确认）AND create_time < 超时时间点
        // 等价 SQL: SELECT * FROM `order` WHERE status = 0 AND create_time < ? AND deleted = 0
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getStatus, OrderStatus.PENDING)
                .lt(Order::getCreateTime, timeoutTime);

        List<Order> timeoutOrders = orderMapper.selectList(wrapper);

        // 没有超时订单 → 直接返回，不做任何操作（避免无意义的日志刷屏）
        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("检测到 {} 笔超时未确认订单，开始自动取消（超时阈值={}分钟）", timeoutOrders.size(), TIMEOUT_MINUTES);

        // ===== 第 3 步：逐笔处理（每笔独立 try-catch，一笔失败不影响其他）=====
        int successCount = 0;
        int failCount = 0;

        for (Order order : timeoutOrders) {
            try {
                // 3a. 条件更新取消订单：只有订单仍为 PENDING 时才取消
                // 等价 SQL: UPDATE `order` SET status = 2 WHERE id = ? AND status = 0
                // 如果在查询和取消之间卖家已确认了订单（status 变为 1），
                // 这条 UPDATE 影响行数 = 0，不会误伤已确认的订单
                LambdaUpdateWrapper<Order> orderUpdate = new LambdaUpdateWrapper<>();
                orderUpdate.eq(Order::getId, order.getId())
                        .eq(Order::getStatus, OrderStatus.PENDING)  // 关键：只取消仍为“待确认”的
                        .set(Order::getStatus, OrderStatus.CANCELED);
                int updated = orderMapper.update(null, orderUpdate);

                if (updated == 0) {
                    // 订单状态已变（卖家在超时前确认了），跳过不处理
                    log.info("订单状态已变更，跳过取消：orderId={}, orderNo={}", order.getId(), order.getOrderNo());
                    continue;
                }

                // 3b. 释放商品：状态 LOCKED → ON_SALE，让其他买家可以重新购买
                //     只 new 一个 Product 并设置 id + status，updateById 只会更新这两个字段
                Product product = new Product();
                product.setId(order.getProductId());
                product.setStatus(ProductStatus.ON_SALE);
                productMapper.updateById(product);

                successCount++;
                log.info("超时订单已自动取消：orderId={}, orderNo={}, productId={}, 创建时间={}",
                        order.getId(), order.getOrderNo(), order.getProductId(), order.getCreateTime());
            } catch (Exception e) {
                // 单笔失败不影响其他订单：记录错误日志，继续处理下一笔
                failCount++;
                log.error("超时订单取消失败：orderId={}, orderNo={}, 原因={}",
                        order.getId(), order.getOrderNo(), e.getMessage(), e);
            }
        }

        log.info("超时订单自动取消完成：成功 {} 笔，失败 {} 笔", successCount, failCount);
    }
}
