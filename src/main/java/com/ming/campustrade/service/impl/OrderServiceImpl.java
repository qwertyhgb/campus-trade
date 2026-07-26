package com.ming.campustrade.service.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.OrderStatus;
import com.ming.campustrade.common.constant.ProductStatus;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.OrderPlaceDTO;
import com.ming.campustrade.entity.Order;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.OrderMapper;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.service.OrderService;
import com.ming.campustrade.service.ProductCacheService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service // 告诉 Spring 容器，这是一个业务逻辑层（Service）的组件，Spring 会自动扫描并创建它的实例（Bean）
@SuppressWarnings("null") // 抑制编译器关于 Null 指针类型安全的警告（通常在使用 MyBatis-Plus Lambda 表达式时产生）
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    // 声明商品和用户的数据访问接口（Mapper），用于跨模块的数据库操作
    // 使用 final 修饰，确保在构造函数初始化后不能再被修改，符合安全和不变性原则
    private final ProductMapper productMapper;
    private final UserMapper userMapper;
    // 商品详情缓存组件：订单流程会改变商品状态（锁定/售出/释放），需同步清除商品缓存
    private final ProductCacheService productCacheService;

    /**
     * 构造函数注入（Spring 推荐的依赖注入方式）：
     * Spring 在实例化 OrderServiceImpl 时，会自动查找容器中的这些实例并注入进来
     */
    public OrderServiceImpl(ProductMapper productMapper, UserMapper userMapper, ProductCacheService productCacheService) {
        this.productMapper = productMapper;
        this.userMapper = userMapper;
        this.productCacheService = productCacheService;
    }

    /**
     * 创建订单 - 核心业务逻辑
     * 
     * 流程说明：
     * 1. 获取当前登录用户（买家）的 ID
     * 2. 验证商品是否存在
     * 3. 防止用户购买自己的商品
     * 4. 原子性地更新商品状态为已锁定（防止超卖）
     * 5. 生成订单号并创建订单记录
     * 
     * @param orderPlaceDTO 下单请求参数，包含商品 ID
     * @throws BusinessException 业务异常（商品不存在、自买自卖、商品不可用等）
     */
    @Override
    @Transactional // 声明式事务：该方法内所有的数据库操作必须在同一个事务中运行，若其中任何一步报错，所有操作都会回滚，保证数据一致性
    public void placeOrder(OrderPlaceDTO orderPlaceDTO) {
        // 1. 从线程局部变量 ThreadLocal 中获取当前登录用户（买家）的 ID（已由拦截器提前注入）
        Long buyerId = UserHolder.getUserVO().getId();
        log.info("下单：productId={}, buyerId={}", orderPlaceDTO.getProductId(), buyerId);

        // 2. 根据商品 ID 查询商品信息
        Product product = productMapper.selectById(orderPlaceDTO.getProductId());
        if (product == null) {
            // 商品不存在，抛出业务异常（框架捕获后会转换为友好的 JSON 返回给前端）
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 3. 验证买家不能购买自己的商品（防止自买自卖）
        if (product.getSellerId().equals(buyerId)) {
            throw new BusinessException(ResultCode.CANNOT_BUY_OWN_PRODUCT);
        }

        // 4. 使用乐观锁机制，原子性地将商品状态从 ON_SALE(1) 更新为 LOCKED(2)
        // 防止并发下单时的超卖问题（例如：同一件二手商品被多个用户同时购买，只有一个人能更新成功）
        LambdaUpdateWrapper<Product> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Product::getId, product.getId()) // 条件1：商品 ID 必须匹配
                .eq(Product::getStatus, ProductStatus.ON_SALE) // 条件2：商品必须依然是“在售”状态，只有这样才能购买
                .set(Product::getStatus, ProductStatus.LOCKED); // 更新操作：将商品状态修改为“已锁定”（锁定后他人不能再购买）
        
        // 执行更新操作，返回受影响的行数
        int updated = productMapper.update(null, updateWrapper);

        // 5. 检查更新是否成功。如果返回 0 说明没有行被更新，说明商品已被他人手快先下过单，商品状态已变，购买失败
        if (updated == 0) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_AVAILABLE);
        }

        // 6. 生成唯一的订单号
        // 格式：ORD + 当前时间毫秒戳 + 4位随机数（基本保证了并发下单时的唯一性）
        String orderNo = "ORD" + System.currentTimeMillis() + (int) (Math.random() * 9000 + 1000);

        // 7. 创建订单对象并填充数据
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setProductId(product.getId());
        
        // 8. 保存商品快照数据（下单时刻的商品信息）
        // 目的：订单生成后，即使卖家事后修改了商品详情、价格或图片，这笔交易订单中记录的依然是下单瞬间的历史快照，方便纠纷追溯
        order.setProductTitle(product.getTitle());           // 快照：下单时的标题
        order.setProductPrice(product.getPrice());           // 快照：下单时的价格
        order.setProductImage(product.getImage());           // 快照：下单时的图片
        
        // 9. 记录买卖双方的用户 ID
        order.setBuyerId(buyerId);                           // 买家 ID
        order.setSellerId(product.getSellerId());            // 卖家 ID（来自商品信息）
        
        // 10. 初始化订单状态为 PENDING(0)，即待确认状态
        // 卖家需要确认订单后，买家才能付款
        order.setStatus(OrderStatus.PENDING);

        // 11. 保存订单到数据库（ServiceImpl 提供的 save 方法，实际底层会调用 orderMapper.insert）
        this.save(order);
        // 商品已被锁定（ON_SALE→LOCKED），清除详情缓存避免其他用户看到过期的“在售”状态
        productCacheService.evict(order.getProductId());
        log.info("下单成功：orderId={}, orderNo={}, productId={}", order.getId(), order.getOrderNo(), order.getProductId());
    }

    /**
     * 卖家确认订单 - 订单交易流程的第二步
     * 
     * 流程说明：
     * 1. 查询订单是否存在
     * 2. 验证当前用户是否是卖家（权限检查）
     * 3. 验证订单状态是否为待确认状态
     * 4. 将订单状态改为已确认
     * 5. 将商品状态改为已售出
     * 
     * @param id 订单 ID
     * @throws BusinessException 业务异常（订单不存在、无权操作、订单状态错误等）
     */
    @Override
    @Transactional // 涉及两张表的操作（更新订单状态、更新商品状态），必须开启事务
    public void confirmOrder(Long id) {
        log.info("确认订单：orderId={}", id);
        // 1. 根据订单 ID 查询订单记录
        Order order = this.getById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        // 2. 获取当前登录用户的 ID
        Long currentUserId = UserHolder.getUserVO().getId();
        
        // 3. 权限验证：只有该订单的卖家，才能确认此订单
        // 防止买家或其他用户伪造请求来帮卖家做决定
        if (!order.getSellerId().equals(currentUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作此订单");
        }

        // 4. 状态验证：订单必须处于待确认（PENDING = 0）状态才可以进行确认操作
        // 防止已经取消或已成单的订单被卖家恶意重复触发状态流转
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态不允许确认");
        }

        // 5. 条件更新订单状态：PENDING → CONFIRMED
        //    用条件更新（WHERE status=PENDING）而非无条件 updateById，防止并发竞态：
        //    如果买家同一时刻取消了订单（status 已变为 CANCELED），这条 UPDATE 影响行数=0，
        //    不会把已取消的订单错误地改成已确认。只有第一个到达的请求能成功。
        boolean orderUpdated = this.lambdaUpdate()
                .eq(Order::getId, id)
                .eq(Order::getStatus, OrderStatus.PENDING)
                .set(Order::getStatus, OrderStatus.CONFIRMED)
                .update();
        if (!orderUpdated) {
            // 影响行数=0：订单状态已被并发修改（如被取消），拒绝确认
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变更，无法确认");
        }

        // 6. 条件更新商品状态：LOCKED → SOLD
        //    同样用条件更新，只有商品仍为“锁定”时才改为“已售”。
        //    如果商品状态异常（不是 LOCKED），影响行数=0，抛异常触发事务回滚，
        //    连带撤销第 5 步的订单确认，保证两表数据一致。
        int productUpdated = productMapper.update(null,
                new LambdaUpdateWrapper<Product>()
                        .eq(Product::getId, order.getProductId())
                        .eq(Product::getStatus, ProductStatus.LOCKED)
                        .set(Product::getStatus, ProductStatus.SOLD));
        if (productUpdated == 0) {
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "商品状态异常，确认失败");
        }
        // 商品已售出（LOCKED→SOLD），清除详情缓存
        productCacheService.evict(order.getProductId());
        log.info("确认订单成功：orderId={}, productId={}", id, order.getProductId());
    }

    /**
     * 取消订单
     * 
     * 流程说明：
     * 1. 检验订单是否存在
     * 2. 只有该订单的买家或卖家才有权取消
     * 3. 订单状态必须为“待确认”
     * 4. 释放商品（把锁定状态变回在售状态），取消订单
     */
    @Override
    @Transactional // 涉及更新订单与商品状态，必须加事务
    public void cancelOrder(Long id) {
        // 1. 获取订单记录
        Order order = this.getById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        // 2. 身份校验：仅买家或卖家可取消订单
        Long currentUserId = UserHolder.getUserVO().getId();
        boolean isBuyer = order.getBuyerId().equals(currentUserId);
        boolean isSeller = order.getSellerId().equals(currentUserId);
        if (!isBuyer && !isSeller) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作此订单");
        }

        // 3. 状态校验：只有“待确认”状态的订单才可以取消
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态不允许取消");
        }

        // 4. 条件更新订单状态：PENDING → CANCELED
        //    用条件更新防止并发竞态：如果卖家同一时刻确认了订单（status 已变为 CONFIRMED），
        //    这条 UPDATE 影响行数=0，不会把已确认的订单错误地取消。
        boolean orderUpdated = this.lambdaUpdate()
                .eq(Order::getId, id)
                .eq(Order::getStatus, OrderStatus.PENDING)
                .set(Order::getStatus, OrderStatus.CANCELED)
                .update();
        if (!orderUpdated) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变更，无法取消");
        }

        // 5. 条件释放商品：LOCKED → ON_SALE，让其他同学可以继续购买
        //    只有商品仍为“锁定”时才释放；若状态异常则抛异常回滚整个事务（连带撤销订单取消）
        int productUpdated = productMapper.update(null,
                new LambdaUpdateWrapper<Product>()
                        .eq(Product::getId, order.getProductId())
                        .eq(Product::getStatus, ProductStatus.LOCKED)
                        .set(Product::getStatus, ProductStatus.ON_SALE));
        if (productUpdated == 0) {
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "商品状态异常，取消失败");
        }
        // 商品已释放（LOCKED→ON_SALE），清除详情缓存
        productCacheService.evict(order.getProductId());
        log.info("取消订单成功：orderId={}, productId={}", id, order.getProductId());
    }

    /**
     * 根据订单 ID 获取订单详情
     * 
     * @param id 订单 ID
     * @return 组装完成的 OrderVO 视图对象（包含买卖双方昵称等敏感/跨表信息）
     */
    @Override
    public OrderVO getOrderById(Long id) {
        log.info("查询订单详情：orderId={}", id);
        // 1. 查询订单
        Order order = this.getById(id);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        // 2. 身份校验：只有这笔交易相关的买家或者卖家，才可以查看订单详情
        Long currentUserId = UserHolder.getUserVO().getId();
        boolean isBuyer = order.getBuyerId().equals(currentUserId);
        boolean isSeller = order.getSellerId().equals(currentUserId);
        if (!isBuyer && !isSeller) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看此订单");
        }

        // 3. 跨表查询买家和卖家的详细资料（获取头像昵称等）
        User buyer = userMapper.selectById(order.getBuyerId());
        User seller = userMapper.selectById(order.getSellerId());

        // 4. 将查出来的实体通过辅助函数转换为 OrderVO 对象返回给前端
        return convertOrderVO(order, buyer, seller);
    }

    /**
     * 分页查询买家订单（“我买到的”订单列表）
     * 
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 包含 OrderVO 数据的分页对象
     */
    @Override
    public IPage<OrderVO> getBuyOrder(Integer pageNo, Integer pageSize) {
        log.info("查询我买到的订单：buyerId={}, pageNo={}, pageSize={}",
                UserHolder.getUserVO().getId(), pageNo, pageSize);
        // 1. 获取当前登录买家的 ID
        Long buyerId = UserHolder.getUserVO().getId();

        // 2. 构造查询条件：buyer_id 等于当前登录用户，且按创建时间逆序排列（最新下单的在最上面）
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getBuyerId, buyerId).orderByDesc(Order::getCreateTime);

        // 3. 初始化 MyBatis-Plus 分页插件参数对象
        Page<Order> page = new Page<>(pageNo, pageSize);
        // 执行分页查询，查询出的原始 Order 列表会自动封装进 orderPage
        Page<Order> orderPage = this.page(page, wrapper);

        // 4. 性能优化（避免在 for 循环中多次执行 selectById 查库）：
        // 从当前页的所有订单中收集所有不重复（distinct）的卖家 ID 列表
        List<Long> sellerIds = orderPage.getRecords().stream()
                                .map(Order::getSellerId)
                                .distinct()
                                .toList();
        
        // 根据收集齐的卖家 ID 列表，发起一次批量查询（selectBatchIds），并将结果映射为 Map：Map<UserId, UserEntity>
        // 这样做无论当前页有多少单，只需要进行一次 SQL 查卖家库
        Map<Long, User> sellerMap = sellerIds.isEmpty() ? Map.of() : userMapper.selectByIds(sellerIds).stream()
                                             .collect(Collectors.toMap(User::getId, u -> u));
        
        // 5. 跨表查询当前买家的信息
        User buyer = userMapper.selectById(buyerId);

        // 6. 将查出的 Order 实体列表批量映射成 VO 列表，VO 中需要关联上一步查到的买家和卖家昵称等
        List<OrderVO> voList = orderPage.getRecords().stream()
                                .map(order -> convertOrderVO(order, buyer, sellerMap.get(order.getSellerId())))
                                .toList();
        
        // 7. 将转换好的 VO 列表装填进一个新的分页对象中，并拷贝总记录数等分页原数据后返回
        Page<OrderVO> voPage = new Page<>();
        voPage.setRecords(voList);
        voPage.setTotal(orderPage.getTotal());
        voPage.setCurrent(orderPage.getCurrent());
        voPage.setSize(orderPage.getSize());

        return voPage;
    }

    /**
     * 分页查询卖家订单（“我卖出的”订单列表）
     * 
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 包含 OrderVO 数据的分页对象
     */
    @Override
    public IPage<OrderVO> getSellOrder(Integer pageNo, Integer pageSize) {
        log.info("查询我卖出的订单：sellerId={}, pageNo={}, pageSize={}",
                UserHolder.getUserVO().getId(), pageNo, pageSize);
        // 1. 获取当前登录卖家的 ID
        Long sellerId = UserHolder.getUserVO().getId();

        // 2. 构造查询条件：seller_id 等于当前登录用户，且按创建时间逆序排列
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getSellerId, sellerId).orderByDesc(Order::getCreateTime);

        // 3. 执行分页查询
        Page<Order> page = new Page<>(pageNo, pageSize);
        Page<Order> orderPage = this.page(page, wrapper);

        // 4. 收集当前页订单中的买家 ID 列表，并进行去重
        List<Long> buyerIds = orderPage.getRecords().stream()
                                .map(Order::getBuyerId)
                                .distinct()
                                .toList();
        
        // 批量查询买家数据，做成映射 Map 供后续拼装 VO 时高效取值
        Map<Long, User> buyerMap = buyerIds.isEmpty() ? Map.of() : userMapper.selectByIds(buyerIds).stream()
                                            .collect(Collectors.toMap(User::getId, u -> u));
        
        // 5. 查询卖家自身信息
        User seller = userMapper.selectById(sellerId);

        // 6. 转换数据：将 Order 转为 OrderVO，并从 map 中拉取对应的买家信息
        List<OrderVO> voList = orderPage.getRecords().stream()
                                .map(order -> convertOrderVO(order, buyerMap.get(order.getBuyerId()), seller))
                                .toList();
        
        // 7. 装填分页对象并返回
        Page<OrderVO> voPage = new Page<>();
        voPage.setRecords(voList);
        voPage.setTotal(orderPage.getTotal());
        voPage.setCurrent(orderPage.getCurrent());
        voPage.setSize(orderPage.getSize());

        return voPage;
    }

    // ==================== 管理员：全部订单 ====================

    /**
     * 管理员分页查询平台全部订单（可按状态筛选）
     *
     * <p><b>和 getBuyOrder/getSellOrder 的区别：</b></p>
     * <ul>
     *   <li>getBuyOrder/getSellOrder：面向普通用户，只查自己作为买家/卖家的订单</li>
     *   <li>listOrdersForAdmin：面向管理员，查平台全部订单，不限买卖方，支持按状态筛选</li>
     * </ul>
     *
     * <p><b>性能优化：</b>与买家/卖家订单列表一样，采用批量查询避免 N+1：
     * 先收集当前页所有不重复的买家 ID 和卖家 ID，各发一次 selectByIds 批量查出，
     * 再转成 Map 供 O(1) 查找。</p>
     *
     * @param status   订单状态筛选（null 表示查全部状态）
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @Override
    public IPage<OrderVO> listOrdersForAdmin(Integer status, Integer pageNo, Integer pageSize) {
        log.info("管理员查询全部订单：status={}, pageNo={}, pageSize={}", status, pageNo, pageSize);

        // 1. 构建查询条件：可选状态筛选 + 按创建时间倒序
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        wrapper.orderByDesc(Order::getCreateTime);

        // 2. 分页查询
        Page<Order> page = new Page<>(pageNo, pageSize);
        Page<Order> orderPage = this.page(page, wrapper);

        // 3. 收集当前页所有不重复的买家 ID 和卖家 ID
        List<Long> buyerIds = orderPage.getRecords().stream()
                .map(Order::getBuyerId)
                .distinct()
                .toList();
        List<Long> sellerIds = orderPage.getRecords().stream()
                .map(Order::getSellerId)
                .distinct()
                .toList();

        // 4. 批量查询买家和卖家信息，转成 Map 供后续快速查找
        Map<Long, User> buyerMap = buyerIds.isEmpty() ? Map.of()
                : userMapper.selectByIds(buyerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));
        Map<Long, User> sellerMap = sellerIds.isEmpty() ? Map.of()
                : userMapper.selectByIds(sellerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        // 5. 转换为 VO 列表（从两个 Map 中分别取买家/卖家信息）
        List<OrderVO> voList = orderPage.getRecords().stream()
                .map(order -> convertOrderVO(order,
                        buyerMap.get(order.getBuyerId()),
                        sellerMap.get(order.getSellerId())))
                .toList();

        // 6. 装填分页对象并返回
        Page<OrderVO> voPage = new Page<>();
        voPage.setRecords(voList);
        voPage.setTotal(orderPage.getTotal());
        voPage.setCurrent(orderPage.getCurrent());
        voPage.setSize(orderPage.getSize());

        return voPage;
    }

    // ==================== 定时任务：超时自动取消 ====================

    /**
     * 超时自动取消单笔订单（每笔独立事务）
     *
     * <p><b>为什么用 REQUIRES_NEW？</b><br>
     * 定时任务会逐笔调用本方法。每笔订单需要一个独立事务：
     * “订单取消 + 商品释放”两步要么都成功要么都回滚，
     * 且某一笔失败不能影响其他订单。REQUIRES_NEW 会为每次调用开启一个全新事务，
     * 与调用方（定时任务）的事务隔离。</p>
     *
     * <p><b>为什么本方法必须放在独立的 Spring Bean（OrderServiceImpl）而不是定时任务类里？</b><br>
     * Spring 的 @Transactional 基于 AOP 代理实现。如果在同一个类内部调用
     * 带 @Transactional 的方法（this.xxx()），会绕过代理导致事务失效。
     * 定时任务类（OrderTimeoutTask）通过注入的 OrderService 调用本方法，
     * 是跨 Bean 调用，代理生效，事务才会真正起作用。</p>
     *
     * @param order 超时订单
     * @return true=已成功取消；false=订单状态已变更（跳过）
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoCancelTimeoutOrder(Order order) {
        // 1. 条件更新订单：PENDING → CANCELED
        //    只有订单仍为“待确认”时才取消；若卖家已在超时前确认（status 变了），影响行数=0
        boolean orderUpdated = this.lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, OrderStatus.PENDING)
                .set(Order::getStatus, OrderStatus.CANCELED)
                .update();
        if (!orderUpdated) {
            // 订单状态已变更（如已被确认），无需取消，返回 false 让调用方跳过
            log.info("超时取消跳过，订单状态已变更：orderId={}, orderNo={}", order.getId(), order.getOrderNo());
            return false;
        }

        // 2. 条件释放商品：LOCKED → ON_SALE
        //    若商品状态异常（不是 LOCKED），影响行数=0，抛异常触发本笔事务回滚，
        //    连带撤销第 1 步的订单取消，避免“订单已取消但商品仍锁定”的不一致
        int productUpdated = productMapper.update(null,
                new LambdaUpdateWrapper<Product>()
                        .eq(Product::getId, order.getProductId())
                        .eq(Product::getStatus, ProductStatus.LOCKED)
                        .set(Product::getStatus, ProductStatus.ON_SALE));
        if (productUpdated == 0) {
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "商品状态异常，超时取消回滚");
        }

        // 商品已释放（LOCKED→ON_SALE），清除详情缓存
        productCacheService.evict(order.getProductId());
        log.info("超时订单已自动取消：orderId={}, orderNo={}, productId={}",
                order.getId(), order.getOrderNo(), order.getProductId());
        return true;
    }
    
    /**
     * 内部辅助工具方法：将数据库实体（Entity）对象转换为传输视图（VO）对象
     * 并将买家、卖家的关联表信息（昵称等）拼装进 VO，隐藏数据库内部敏感字段
     */
    private static OrderVO convertOrderVO(Order order, User buyer, User seller) {
        OrderVO vo = new OrderVO();
        // 复制基础的订单属性
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setProductId(order.getProductId());
        vo.setProductTitle(order.getProductTitle());
        vo.setProductPrice(order.getProductPrice());
        vo.setProductImage(order.getProductImage());
        vo.setStatus(order.getStatus());
        vo.setCreateTime(order.getCreateTime());

        // 拼装买家关联信息
        if (buyer != null) {
            vo.setBuyerId(buyer.getId());
            vo.setBuyerNickname(buyer.getNickname());
        }
        // 拼装卖家关联信息
        if (seller != null) {
            vo.setSellerId(seller.getId());
            vo.setSellerNickname(seller.getNickname());
        }

        return vo;
    }
}
