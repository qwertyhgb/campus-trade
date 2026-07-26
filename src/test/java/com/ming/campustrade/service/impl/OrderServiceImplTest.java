package com.ming.campustrade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.repository.CrudRepository;
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
import com.ming.campustrade.service.ProductCacheService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link OrderServiceImpl} 的单元测试
 *
 * <p>测试策略：Mock 所有 Mapper 依赖（{@link OrderMapper}、{@link ProductMapper}、
 * {@link UserMapper}），测试下单、确认、取消、查询等核心交易流程。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl - 订单服务")
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ProductCacheService productCacheService;

    private OrderServiceImpl orderService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    // ==================== 测试数据 ====================

    private static final Long BUYER_ID = 100L;
    private static final Long SELLER_ID = 200L;
    private static final Long PRODUCT_ID = 1L;
    private static final Long ORDER_ID = 10L;

    /**
     * 初始化 MyBatis-Plus 表元数据（TableInfo）。
     * LambdaUpdateWrapper / LambdaQueryWrapper 在解析 Lambda 表达式（如 Product::getId）时，
     * 需要 TableInfo 将方法引用映射为数据库列名。纯 Mockito 环境没有 Spring 容器自动初始化，
     * 必须手动调用 TableInfoHelper.initTableInfo() 注册实体元数据。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
        TableInfoHelper.initTableInfo(assistant, Order.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        UserVO currentUser = new UserVO();
        currentUser.setId(BUYER_ID);
        UserHolder.saveUser(currentUser);

        orderService = new OrderServiceImpl(productMapper, userMapper, productCacheService);
        Field field = CrudRepository.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(orderService, orderMapper);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private Product onSaleProduct() {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        p.setTitle("在售商品");
        p.setPrice(BigDecimal.valueOf(100));
        p.setSellerId(SELLER_ID);
        p.setStatus(ProductStatus.ON_SALE);
        return p;
    }

    private Product lockedProduct() {
        Product p = onSaleProduct();
        p.setStatus(ProductStatus.LOCKED);
        return p;
    }

    private Order pendingOrder() {
        Order o = new Order();
        o.setId(ORDER_ID);
        o.setBuyerId(BUYER_ID);
        o.setSellerId(SELLER_ID);
        o.setProductId(PRODUCT_ID);
        o.setStatus(OrderStatus.PENDING);
        o.setProductPrice(BigDecimal.valueOf(100));
        return o;
    }

    private OrderPlaceDTO placeDTO() {
        OrderPlaceDTO dto = new OrderPlaceDTO();
        dto.setProductId(PRODUCT_ID);
        return dto;
    }

    // ==================== 下单 ====================

    @Nested
    @DisplayName("placeOrder 下单")
    class PlaceOrder {

        @Test
        @DisplayName("成功下单（在售 → 锁定 + 创建订单）")
        void shouldPlaceOrderSuccessfully() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(onSaleProduct());
            // 原子更新商品状态：update(null, updateWrapper)，第一个参数为 null 实体
            when(productMapper.update(isNull(), any())).thenReturn(1);

            orderService.placeOrder(placeDTO());

            verify(orderMapper).insert((Order) orderCaptor.capture());
            Order saved = orderCaptor.getValue();
            assertThat(saved.getBuyerId()).isEqualTo(BUYER_ID);
            assertThat(saved.getSellerId()).isEqualTo(SELLER_ID);
            assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(saved.getProductPrice()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("商品不存在时抛出 PRODUCT_NOT_FOUND")
        void shouldThrowWhenProductNotFound() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

            assertThatThrownBy(() -> orderService.placeOrder(placeDTO()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_FOUND.getCode());
            verify(orderMapper, never()).insert(any(Order.class));
        }

        @Test
        @DisplayName("购买自己的商品时抛出 CANNOT_BUY_OWN_PRODUCT")
        void shouldThrowWhenBuyingOwnProduct() {
            Product ownProduct = onSaleProduct();
            ownProduct.setSellerId(BUYER_ID); // 自己卖自己买
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(ownProduct);

            assertThatThrownBy(() -> orderService.placeOrder(placeDTO()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.CANNOT_BUY_OWN_PRODUCT.getCode());
        }

        @Test
        @DisplayName("商品不在在售状态时抛出 PRODUCT_NOT_AVAILABLE（更新条件不匹配）")
        void shouldThrowWhenProductNotOnSale() {
            Product offSaleProduct = onSaleProduct();
            offSaleProduct.setStatus(ProductStatus.OFF_SALE);
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(offSaleProduct);
            when(productMapper.update(isNull(), any())).thenReturn(0);

            assertThatThrownBy(() -> orderService.placeOrder(placeDTO()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_AVAILABLE.getCode());
            verify(orderMapper, never()).insert(any(Order.class));
        }

        @Test
        @DisplayName("商品已被他人锁定/已售时抛出 PRODUCT_NOT_AVAILABLE")
        void shouldThrowWhenProductLocked() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(lockedProduct());
            when(productMapper.update(isNull(), any())).thenReturn(0);

            assertThatThrownBy(() -> orderService.placeOrder(placeDTO()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_AVAILABLE.getCode());
            verify(orderMapper, never()).insert(any(Order.class));
        }
    }

    // ==================== 确认订单 ====================

    @Nested
    @DisplayName("confirmOrder 确认订单")
    class ConfirmOrder {

        @Test
        @DisplayName("卖家成功确认订单（待确认 → 已完成 + 商品置为已售）")
        void shouldConfirmOrderSuccessfully() {
            // 切换当前用户为卖家
            UserHolder.removeUser();
            UserVO seller = new UserVO();
            seller.setId(SELLER_ID);
            UserHolder.saveUser(seller);

            when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
            // 条件更新：订单 PENDING→CONFIRMED、商品 LOCKED→SOLD 都成功（影响行数=1）
            when(orderMapper.update(isNull(), any())).thenReturn(1);
            when(productMapper.update(isNull(), any())).thenReturn(1);

            orderService.confirmOrder(ORDER_ID);

            // 验证订单和商品的条件更新都被执行
            verify(orderMapper).update(isNull(), any());
            verify(productMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("并发下订单状态已变更时确认失败（条件更新影响行数=0）")
        void shouldThrowWhenConfirmRaceLost() {
            UserHolder.removeUser();
            UserVO seller = new UserVO();
            seller.setId(SELLER_ID);
            UserHolder.saveUser(seller);

            when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
            // 订单条件更新影响行数=0（如已被买家取消）
            when(orderMapper.update(isNull(), any())).thenReturn(0);

            assertThatThrownBy(() -> orderService.confirmOrder(ORDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.ORDER_STATUS_ERROR.getCode());
            verify(productMapper, never()).update(isNull(), any());
        }

        @Test
        @DisplayName("买家不能确认订单（只允许卖家确认）")
        void shouldThrowWhenNotSeller() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());

            assertThatThrownBy(() -> orderService.confirmOrder(ORDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN.getCode());
        }

        @Test
        @DisplayName("订单不存在时抛出 ORDER_NOT_FOUND")
        void shouldThrowWhenOrderNotFound() {
            when(orderMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> orderService.confirmOrder(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.ORDER_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("非待确认状态的订单不允许确认")
        void shouldThrowWhenOrderNotPending() {
            UserHolder.removeUser();
            UserVO seller = new UserVO();
            seller.setId(SELLER_ID);
            UserHolder.saveUser(seller);

            Order confirmedOrder = pendingOrder();
            confirmedOrder.setStatus(OrderStatus.CONFIRMED);
            when(orderMapper.selectById(ORDER_ID)).thenReturn(confirmedOrder);

            assertThatThrownBy(() -> orderService.confirmOrder(ORDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.ORDER_STATUS_ERROR.getCode());
        }
    }

    // ==================== 取消订单 ====================

    @Nested
    @DisplayName("cancelOrder 取消订单")
    class CancelOrder {

        @Test
        @DisplayName("成功取消待确认订单（商品恢复在售）")
        void shouldCancelPendingOrderSuccessfully() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
            // 条件更新：订单 PENDING→CANCELED、商品 LOCKED→ON_SALE 都成功
            when(orderMapper.update(isNull(), any())).thenReturn(1);
            when(productMapper.update(isNull(), any())).thenReturn(1);

            orderService.cancelOrder(ORDER_ID);

            verify(orderMapper).update(isNull(), any());
            verify(productMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("非待确认状态的订单不能取消")
        void shouldThrowWhenOrderNotPending() {
            Order confirmedOrder = pendingOrder();
            confirmedOrder.setStatus(OrderStatus.CONFIRMED);
            when(orderMapper.selectById(ORDER_ID)).thenReturn(confirmedOrder);

            assertThatThrownBy(() -> orderService.cancelOrder(ORDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.ORDER_STATUS_ERROR.getCode());
        }
    }

    // ==================== 查询订单详情 ====================

    @Nested
    @DisplayName("getOrderById 查询订单详情")
    class GetOrderById {

        @Test
        @DisplayName("成功返回订单 VO")
        void shouldReturnOrder() {
            when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());
            // 查询卖家信息
            User seller = new User();
            seller.setId(SELLER_ID);
            seller.setNickname("卖家");
            when(userMapper.selectById(SELLER_ID)).thenReturn(seller);
            // 查询买家信息
            User buyer = new User();
            buyer.setId(BUYER_ID);
            buyer.setNickname("买家");
            when(userMapper.selectById(BUYER_ID)).thenReturn(buyer);

            var orderVO = orderService.getOrderById(ORDER_ID);

            assertThat(orderVO).isNotNull();
            assertThat(orderVO.getId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("查询不存在的订单时抛出 ORDER_NOT_FOUND")
        void shouldThrowWhenOrderNotFound() {
            when(orderMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> orderService.getOrderById(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.ORDER_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("非交易当事人查看订单时抛出 FORBIDDEN")
        void shouldThrowWhenNotParticipant() {
            // 切换为无关用户
            UserHolder.removeUser();
            UserVO stranger = new UserVO();
            stranger.setId(888L);
            UserHolder.saveUser(stranger);

            when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder());

            assertThatThrownBy(() -> orderService.getOrderById(ORDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN.getCode());
        }
    }

    // ==================== 我买到的订单列表 ====================

    @Nested
    @DisplayName("getBuyOrder 我买到的订单")
    class GetBuyOrder {

        @Test
        @DisplayName("成功返回买家订单分页列表")
        void shouldReturnBuyOrders() {
            Order order = pendingOrder();
            order.setProductTitle("在售商品");

            when(orderMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Order> p = new Page<>(1, 10);
                p.setRecords(List.of(order));
                p.setTotal(1);
                return p;
            });

            User seller = new User();
            seller.setId(SELLER_ID);
            seller.setNickname("卖家");
            when(userMapper.selectByIds(anyList())).thenReturn(List.of(seller));

            User buyer = new User();
            buyer.setId(BUYER_ID);
            buyer.setNickname("买家");
            when(userMapper.selectById(BUYER_ID)).thenReturn(buyer);

            var result = orderService.getBuyOrder(1, 10);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getSellerNickname()).isEqualTo("卖家");
            assertThat(result.getRecords().get(0).getBuyerNickname()).isEqualTo("买家");
            assertThat(result.getTotal()).isEqualTo(1);
        }

        @Test
        @DisplayName("没有订单时返回空列表")
        void shouldReturnEmptyWhenNoBuyOrders() {
            when(orderMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Order> p = new Page<>(1, 10);
                p.setRecords(Collections.emptyList());
                p.setTotal(0);
                return p;
            });

            User buyer = new User();
            buyer.setId(BUYER_ID);
            buyer.setNickname("买家");
            when(userMapper.selectById(BUYER_ID)).thenReturn(buyer);

            var result = orderService.getBuyOrder(1, 10);

            assertThat(result.getRecords()).isEmpty();
            assertThat(result.getTotal()).isZero();
        }
    }

    // ==================== 我卖出的订单列表 ====================

    @Nested
    @DisplayName("getSellOrder 我卖出的订单")
    class GetSellOrder {

        @Test
        @DisplayName("成功返回卖家订单分页列表")
        void shouldReturnSellOrders() {
            // 切换当前用户为卖家
            UserHolder.removeUser();
            UserVO sellerVO = new UserVO();
            sellerVO.setId(SELLER_ID);
            UserHolder.saveUser(sellerVO);

            Order order = pendingOrder();
            order.setProductTitle("在售商品");

            when(orderMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Order> p = new Page<>(1, 10);
                p.setRecords(List.of(order));
                p.setTotal(1);
                return p;
            });

            User buyerUser = new User();
            buyerUser.setId(BUYER_ID);
            buyerUser.setNickname("买家");
            when(userMapper.selectByIds(anyList())).thenReturn(List.of(buyerUser));

            User seller = new User();
            seller.setId(SELLER_ID);
            seller.setNickname("卖家");
            when(userMapper.selectById(SELLER_ID)).thenReturn(seller);

            var result = orderService.getSellOrder(1, 10);

            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getRecords().get(0).getBuyerNickname()).isEqualTo("买家");
            assertThat(result.getRecords().get(0).getSellerNickname()).isEqualTo("卖家");
        }

        @Test
        @DisplayName("没有卖出订单时返回空列表")
        void shouldReturnEmptyWhenNoSellOrders() {
            UserHolder.removeUser();
            UserVO sellerVO = new UserVO();
            sellerVO.setId(SELLER_ID);
            UserHolder.saveUser(sellerVO);

            when(orderMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Order> p = new Page<>(1, 10);
                p.setRecords(Collections.emptyList());
                p.setTotal(0);
                return p;
            });

            User seller = new User();
            seller.setId(SELLER_ID);
            seller.setNickname("卖家");
            when(userMapper.selectById(SELLER_ID)).thenReturn(seller);

            var result = orderService.getSellOrder(1, 10);

            assertThat(result.getRecords()).isEmpty();
        }
    }
}
