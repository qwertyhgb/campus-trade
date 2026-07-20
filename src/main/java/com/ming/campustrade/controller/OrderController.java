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

/**
 * 订单管理控制器 —— 处理订单的创建、确认、取消、查询等 HTTP 请求。
 *
 * <h2>核心注解说明</h2>
 * <ul>
 *   <li>{@code @RestController}：等价于 {@code @Controller + @ResponseBody}。
 *       标注后，该类中所有方法的返回值都会被自动序列化为 JSON 写入响应体，
 *       不需要在每个方法上单独加 {@code @ResponseBody}。
 *       这是 RESTful API 开发的标准写法。</li>
 *   <li>{@code @RequestMapping("/order")}：为该控制器下所有接口设置「基础路径前缀」。
 *       例如方法上映射 {@code @PostMapping("/place")}，
 *       最终完整路径就是 {@code POST /order/place}。</li>
 *   <li>{@code @Tag}：Swagger/Knife4j 注解，用于在接口文档中对接口进行「分组」。
 *       打开 http://localhost:8080/doc.html 后，左侧导航栏会按 Tag 名称分组显示。</li>
 * </ul>
 *
 * <h2>依赖注入方式</h2>
 * <p>
 * 本类使用「构造器注入」（Constructor Injection）而非 {@code @Autowired} 字段注入。
 * 原因：
 * <ol>
 *   <li>字段可以声明为 {@code final}，保证不可变性，线程安全；</li>
 *   <li>如果忘记注入，启动时就会报错（NullPointerException），而不是运行时才炸；</li>
 *   <li>方便单元测试时直接 new 出来传入 mock 对象。</li>
 * </ol>
 * </p>
 *
 * <h2>权限约定</h2>
 * <p>
 * 本控制器所有接口都「需要登录」（没有 @PublicApi 标记），
 * 因为订单涉及买卖双方的私有数据，不允许未登录用户访问。
 * 具体的「只能看自己的订单」逻辑在 Service 层通过当前用户 ID 过滤实现。
 * </p>
 *
 * <h2>订单状态流转</h2>
 * <pre>
 *   下单(place) → 待确认(PENDING) → 卖家确认(confirm) → 已完成(COMPLETED)
 *                                  → 买家/卖家取消(cancel) → 已取消(CANCELLED)
 * </pre>
 *
 * @author Ming
 * @since 1.0.0
 */
@Slf4j
@Tag(name = "订单管理", description = "订单的创建、确认、取消、查询等操作")
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;

    /**
     * 构造器注入：Spring 启动时自动把 OrderService 的实现类实例传进来。
     * 因为只有一个构造器，所以不需要额外加 @Autowired 注解（Spring 4.3+ 特性）。
     */
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 下单 —— 买家购买商品（需要登录）。
     *
     * <p>{@code @RequestBody} 从请求体读取 JSON 并反序列化为 OrderPlaceDTO 对象。</p>
     * <p>{@code @Valid} 触发 Jakarta Validation 校验：DTO 上的 @NotNull 等注解
     * 会在此处自动生效，校验不通过直接返回 400 错误，不会进入方法体。</p>
     * <p>下单时 Service 层会：① 检查商品是否在售 ② 锁定商品（防并发超卖） ③ 创建订单记录。</p>
     *
     * @param orderPlaceDTO 下单信息（商品ID、收货地址等）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "下单", description = "买家购买商品，创建订单并锁定商品库存")
    @PostMapping("/place")
    public Result<Void> placeOrder(@RequestBody @Valid OrderPlaceDTO orderPlaceDTO) {
        log.info("下单：productId={}", orderPlaceDTO.getProductId());
        orderService.placeOrder(orderPlaceDTO);
        log.info("下单成功：productId={}", orderPlaceDTO.getProductId());
        return Result.success();
    }

    /**
     * 确认订单 —— 卖家操作（需要登录）。
     *
     * <p>{@code @PutMapping("/{id}/confirm")} 使用 PUT 方法 + 子路径，
     * 语义是「更新订单状态为已确认」。</p>
     * <p>{@code @PathVariable} 从 URL 路径中提取订单 ID：
     * 例如请求 PUT /order/42/confirm，则 id = 42。</p>
     * <p>确认后：订单状态 → COMPLETED，商品状态 → 已售出。</p>
     *
     * @param id 订单ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "确认订单", description = "卖家确认订单，订单状态变为已完成，商品标记为已售出")
    @PutMapping("/{id}/confirm")
    public Result<Void> confirmOrder(@Parameter(description = "订单ID") @PathVariable Long id) {
        log.info("确认订单：orderId={}", id);
        orderService.confirmOrder(id);
        log.info("确认订单成功：orderId={}", id);
        return Result.success();
    }

    /**
     * 取消订单 —— 买家或卖家均可操作（需要登录）。
     *
     * <p>只有「待确认」状态的订单才能被取消。
     * 取消后：订单状态 → CANCELLED，商品恢复为在售状态（释放锁定）。</p>
     *
     * @param id 订单ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "取消订单", description = "买家或卖家取消待确认状态的订单，释放锁定商品")
    @PutMapping("/{id}/cancel")
    public Result<Void> cancelOrder(@Parameter(description = "订单ID") @PathVariable Long id) {
        log.info("取消订单：orderId={}", id);
        orderService.cancelOrder(id);
        log.info("取消订单成功：orderId={}", id);
        return Result.success();
    }

    /**
     * 查询订单详情（需要登录，仅买家或卖家可查看）。
     *
     * <p>Service 层会校验当前用户是否是该订单的买方或卖方，
     * 如果不是则抛出权限异常，防止用户查看他人订单。</p>
     *
     * @param id 订单ID（路径变量）
     * @return 订单详细信息（含商品信息、买卖双方信息、状态等）
     */
    @Operation(summary = "查询订单详情", description = "根据订单ID获取订单详细信息（仅买家或卖家可查看）")
    @GetMapping("/{id}")
    public Result<OrderVO> getOrderById(@Parameter(description = "订单ID") @PathVariable Long id) {
        log.info("查询订单详情：orderId={}", id);
        return Result.success(orderService.getOrderById(id));
    }

    /**
     * 我买到的订单列表（需要登录）。
     *
     * <p>分页查询当前登录用户作为「买家」的所有订单，按下单时间倒序排列。</p>
     * <p>{@code @RequestParam(defaultValue = "1")} 表示如果前端没传这个参数，
     * 就使用默认值，避免空指针。</p>
     *
     * @param pageNo   页码，从1开始（查询参数，默认1）
     * @param pageSize 每页条数（查询参数，默认10）
     * @return 分页的订单列表
     */
    @Operation(summary = "我买到的订单", description = "分页查询当前登录买家购买的订单列表，按时间倒序")
    @GetMapping("/buy")
    public Result<IPage<OrderVO>> getBuyOrder(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer pageNo,
                                                @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("查询我买到的订单：pageNo={}, pageSize={}", pageNo, pageSize);
        return Result.success(orderService.getBuyOrder(pageNo, pageSize));
    }

    /**
     * 我卖出的订单列表（需要登录）。
     *
     * <p>分页查询当前登录用户作为「卖家」的所有订单，按下单时间倒序排列。</p>
     *
     * @param pageNo   页码，从1开始（查询参数，默认1）
     * @param pageSize 每页条数（查询参数，默认10）
     * @return 分页的订单列表
     */
    @Operation(summary = "我卖出的订单", description = "分页查询当前登录卖家卖出的订单列表，按时间倒序")
    @GetMapping("/sell")
    public Result<IPage<OrderVO>> getSellOrder(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer pageNo,
                                                 @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("查询我卖出的订单：pageNo={}, pageSize={}", pageNo, pageSize);
        return Result.success(orderService.getSellOrder(pageNo, pageSize));
    }
}
