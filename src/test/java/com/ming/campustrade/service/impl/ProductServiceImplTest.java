package com.ming.campustrade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.ProductStatus;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.ProductPublishDTO;
import com.ming.campustrade.dto.ProductQueryDTO;
import com.ming.campustrade.dto.ProductUpdateDTO;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.CommentMapper;
import com.ming.campustrade.mapper.FavoriteMapper;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.ProductVO;
import com.ming.campustrade.vo.UserVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ProductServiceImpl} 的单元测试
 *
 * <p>测试策略：Mock 所有外部依赖（{@link ProductMapper}、{@link UserMapper}、
 * {@link StringRedisTemplate}、{@link ObjectMapper}），验证商品发布、编辑、删除、
 * 详情查询（含缓存逻辑）等核心业务。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductServiceImpl - 商品服务")
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private FavoriteMapper favoriteMapper;

    private ProductServiceImpl productService;

    @Captor
    private ArgumentCaptor<Product> productCaptor;

    // ==================== 测试数据 ====================

    private static final Long SELLER_ID = 100L;
    private static final Long PRODUCT_ID = 1L;
    private static final Long OTHER_USER_ID = 999L;

    /**
     * 初始化 MyBatis-Plus 表元数据，使 LambdaQueryWrapper 能正确解析 Product 实体的 Lambda 表达式
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Product.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        UserVO currentUser = new UserVO();
        currentUser.setId(SELLER_ID);
        currentUser.setUsername("seller");
        UserHolder.saveUser(currentUser);

        productService = new ProductServiceImpl(userMapper, stringRedisTemplate, objectMapper, commentMapper, favoriteMapper);
        Field field = CrudRepository.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(productService, productMapper);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private Product createProduct(Long id, Long sellerId, Integer status) {
        Product p = new Product();
        p.setId(id);
        p.setTitle("测试商品");
        p.setPrice(BigDecimal.valueOf(100));
        p.setSellerId(sellerId);
        p.setStatus(status);
        p.setViewCount(0);
        return p;
    }

    private ProductPublishDTO publishDTO() {
        ProductPublishDTO dto = new ProductPublishDTO();
        dto.setTitle("新商品");
        dto.setDescription("描述");
        dto.setPrice(BigDecimal.valueOf(50));
        dto.setConditionLevel(1);
        dto.setCategoryId(1L);
        return dto;
    }

    // ==================== 发布商品 ====================

    @Nested
    @DisplayName("publishProduct 发布商品")
    class PublishProduct {

        @Test
        @DisplayName("成功发布商品，默认待审核、浏览量 0")
        void shouldPublishSuccessfully() {
            ProductPublishDTO dto = publishDTO();

            productService.publishProduct(dto);

            verify(productMapper).insert((Product) productCaptor.capture());
            Product saved = productCaptor.getValue();
            assertThat(saved.getTitle()).isEqualTo("新商品");
            assertThat(saved.getPrice()).isEqualByComparingTo("50");
            assertThat(saved.getSellerId()).isEqualTo(SELLER_ID);
            assertThat(saved.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
            assertThat(saved.getViewCount()).isZero();
        }
    }

    // ==================== 修改商品 ====================

    @Nested
    @DisplayName("updateProduct 修改商品")
    class UpdateProduct {

        @Test
        @DisplayName("成功修改商品标题和价格")
        void shouldUpdateSuccessfully() {
            Product original = createProduct(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(original);

            ProductUpdateDTO dto = new ProductUpdateDTO();
            dto.setTitle("新标题");
            dto.setPrice(BigDecimal.valueOf(200));

            productService.updateProduct(PRODUCT_ID, dto);

            verify(productMapper).updateById((Product) productCaptor.capture());
            Product updated = productCaptor.getValue();
            assertThat(updated.getTitle()).isEqualTo("新标题");
            assertThat(updated.getPrice()).isEqualByComparingTo("200");
            assertThat(updated.getDescription()).isNull(); // 未传，保持 null
        }

        @Test
        @DisplayName("商品不存在时抛出 PRODUCT_NOT_FOUND")
        void shouldThrowWhenNotFound() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

            assertThatThrownBy(() -> productService.updateProduct(PRODUCT_ID, new ProductUpdateDTO()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("非卖家修改时抛出 FORBIDDEN")
        void shouldThrowWhenNotOwner() {
            Product product = createProduct(PRODUCT_ID, OTHER_USER_ID, ProductStatus.ON_SALE);
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);

            assertThatThrownBy(() -> productService.updateProduct(PRODUCT_ID, new ProductUpdateDTO()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN.getCode());
        }
    }

    // ==================== 删除商品 ====================

    @Nested
    @DisplayName("deleteProduct 删除商品")
    class DeleteProduct {

        @Test
        @DisplayName("成功删除商品（逻辑删除），并清除缓存")
        void shouldDeleteSuccessfully() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(createProduct(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE));

            productService.deleteProduct(PRODUCT_ID);

            verify(productMapper).deleteById(PRODUCT_ID);
            verify(stringRedisTemplate).delete(RedisConstants.PRODUCT_DETAIL_KEY + PRODUCT_ID);
        }

        @Test
        @DisplayName("删除不存在的商品时抛出 PRODUCT_NOT_FOUND")
        void shouldThrowWhenNotFound() {
            when(productMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> productService.deleteProduct(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_FOUND.getCode());
            verify(productMapper, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("非卖家删除时抛出 FORBIDDEN")
        void shouldThrowWhenNotOwner() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(createProduct(PRODUCT_ID, OTHER_USER_ID, ProductStatus.ON_SALE));

            assertThatThrownBy(() -> productService.deleteProduct(PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FORBIDDEN.getCode());
        }
    }

    // ==================== 查看商品详情 ====================

    @Nested
    @DisplayName("getProductById 查看商品详情")
    @SuppressWarnings("unchecked")
    class GetProductById {

        @Test
        @DisplayName("缓存命中时直接返回 VO，不走 MySQL")
        void shouldReturnFromCacheWhenCacheHit() throws Exception {
            String cacheKey = RedisConstants.PRODUCT_DETAIL_KEY + PRODUCT_ID;
            String cachedJson = "{\"id\":1,\"title\":\"cached\"}";
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(cacheKey)).thenReturn(cachedJson);

            ProductVO cachedVo = new ProductVO();
            cachedVo.setId(PRODUCT_ID);
            cachedVo.setTitle("cached");
            when(objectMapper.readValue(cachedJson, ProductVO.class)).thenReturn(cachedVo);

            ProductVO result = productService.getProductById(PRODUCT_ID);

            assertThat(result.getTitle()).isEqualTo("cached");
            verify(productMapper, never()).selectById(any()); // 未查 MySQL
            verify(stringRedisTemplate, never()).delete(anyString()); // 未删除缓存
        }

        @Test
        @DisplayName("缓存空值标记时直接抛 PRODUCT_NOT_FOUND（防穿透）")
        void shouldThrowWhenCacheHasNullValue() {
            String cacheKey = RedisConstants.PRODUCT_DETAIL_KEY + PRODUCT_ID;
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(cacheKey)).thenReturn(RedisConstants.PRODUCT_NULL_VALUE);

            assertThatThrownBy(() -> productService.getProductById(PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_FOUND.getCode());
            verify(productMapper, never()).selectById(any()); // 未穿透到 MySQL
        }

        @Test
        @DisplayName("缓存未命中时查 MySQL，并写入 Redis 缓存")
        void shouldQueryDbAndCacheWhenCacheMiss() throws Exception {
            String cacheKey = RedisConstants.PRODUCT_DETAIL_KEY + PRODUCT_ID;
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(cacheKey)).thenReturn(null); // 缓存未命中

            Product product = createProduct(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);

            User seller = new User();
            seller.setId(SELLER_ID);
            seller.setNickname("卖家");
            when(userMapper.selectById(SELLER_ID)).thenReturn(seller);

            String json = "{\"id\":1,\"title\":\"测试商品\"}";
            when(objectMapper.writeValueAsString(any(ProductVO.class))).thenReturn(json);

            ProductVO result = productService.getProductById(PRODUCT_ID);

            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("测试商品");
            assertThat(result.getSellerNickname()).isEqualTo("卖家");
            verify(stringRedisTemplate.opsForValue()).set(eq(cacheKey), eq(json), any(Duration.class));
        }

        @Test
        @DisplayName("商品不存在且在 MySQL 中也查不到时缓存空值（防穿透）")
        void shouldCacheNullValueWhenProductNotFoundInDb() {
            String cacheKey = RedisConstants.PRODUCT_DETAIL_KEY + PRODUCT_ID;
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(cacheKey)).thenReturn(null); // 缓存未命中
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

            assertThatThrownBy(() -> productService.getProductById(PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_FOUND.getCode());

            verify(stringRedisTemplate.opsForValue())
                    .set(cacheKey, RedisConstants.PRODUCT_NULL_VALUE, java.time.Duration.ofMinutes(5));
        }
    }

    // ==================== 修改商品状态 ====================

    @Nested
    @DisplayName("updateStatus 修改商品状态")
    class UpdateStatus {

        @Test
        @DisplayName("成功下架商品（在售 → 下架）")
        void shouldUpdateStatusSuccessfully() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(createProduct(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE));
            // 条件更新影响行数=1（状态转换成功）
            when(productMapper.update(isNull(), any())).thenReturn(1);

            productService.updateStatus(PRODUCT_ID, ProductStatus.OFF_SALE);

            verify(productMapper).update(isNull(), any());
            verify(stringRedisTemplate).delete(RedisConstants.PRODUCT_DETAIL_KEY + PRODUCT_ID); // 缓存失效
        }

        @Test
        @DisplayName("绕过审核的状态变更被拒绝（待审核 → 在售）")
        void shouldRejectIllegalTransition() {
            // 商品当前为待审核，卖家试图直接改为在售（绕过审核）
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(createProduct(PRODUCT_ID, SELLER_ID, ProductStatus.PENDING_REVIEW));

            assertThatThrownBy(() -> productService.updateStatus(PRODUCT_ID, ProductStatus.ON_SALE))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_STATUS_ERROR.getCode());
            verify(productMapper, never()).update(isNull(), any());
        }

        @Test
        @DisplayName("传入非法状态时抛出 PRODUCT_STATUS_ERROR")
        void shouldThrowWhenInvalidStatus() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(createProduct(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE));

            assertThatThrownBy(() -> productService.updateStatus(PRODUCT_ID, 99))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_STATUS_ERROR.getCode());
            verify(productMapper, never()).updateById(any(Product.class));
        }
    }

    // ==================== 商品列表查询 ====================

    @Nested
    @DisplayName("listProducts 商品列表查询")
    @SuppressWarnings("unchecked")
    class ListProducts {

        @Test
        @DisplayName("成功返回分页商品列表，包含卖家信息")
        void shouldReturnProductPage() {
            Product p1 = createProduct(1L, SELLER_ID, ProductStatus.ON_SALE);
            Product p2 = createProduct(2L, 200L, ProductStatus.ON_SALE);

            when(productMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Product> page = new Page<>(1, 10);
                page.setRecords(List.of(p1, p2));
                page.setTotal(2);
                return page;
            });

            User seller1 = new User();
            seller1.setId(SELLER_ID);
            seller1.setNickname("卖家A");
            User seller2 = new User();
            seller2.setId(200L);
            seller2.setNickname("卖家B");
            when(userMapper.selectByIds(anyList())).thenReturn(List.of(seller1, seller2));

            ProductQueryDTO query = new ProductQueryDTO();
            query.setPageNo(1);
            query.setPageSize(10);

            var result = productService.listProducts(query);

            assertThat(result.getRecords()).hasSize(2);
            assertThat(result.getTotal()).isEqualTo(2);
            assertThat(result.getRecords().get(0).getSellerNickname()).isEqualTo("卖家A");
            assertThat(result.getRecords().get(1).getSellerNickname()).isEqualTo("卖家B");
        }

        @Test
        @DisplayName("没有商品时返回空列表")
        void shouldReturnEmptyPage() {
            when(productMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Product> page = new Page<>(1, 10);
                page.setRecords(Collections.emptyList());
                page.setTotal(0);
                return page;
            });

            ProductQueryDTO query = new ProductQueryDTO();
            var result = productService.listProducts(query);

            assertThat(result.getRecords()).isEmpty();
            assertThat(result.getTotal()).isZero();
        }

        @Test
        @DisplayName("带关键词和价格筛选查询正常执行")
        void shouldApplyFilters() {
            when(productMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Product> page = new Page<>(1, 10);
                page.setRecords(Collections.emptyList());
                page.setTotal(0);
                return page;
            });

            ProductQueryDTO query = new ProductQueryDTO();
            query.setKeyword("iPhone");
            query.setMinPrice(BigDecimal.valueOf(100));
            query.setMaxPrice(BigDecimal.valueOf(5000));
            query.setSort("price_asc");

            var result = productService.listProducts(query);

            assertThat(result).isNotNull();
            verify(productMapper).selectPage(any(Page.class), any());
        }
    }

    // ==================== 我的商品 ====================

    @Nested
    @DisplayName("getMyProducts 我的商品")
    @SuppressWarnings("unchecked")
    class GetMyProducts {

        @Test
        @DisplayName("成功返回当前用户的所有商品（包含下架和已售）")
        void shouldReturnMyProducts() {
            Product p1 = createProduct(1L, SELLER_ID, ProductStatus.ON_SALE);
            Product p2 = createProduct(2L, SELLER_ID, ProductStatus.OFF_SALE);

            when(productMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Product> page = new Page<>(1, 10);
                page.setRecords(List.of(p1, p2));
                page.setTotal(2);
                return page;
            });

            User seller = new User();
            seller.setId(SELLER_ID);
            seller.setNickname("我");
            when(userMapper.selectById(SELLER_ID)).thenReturn(seller);

            var result = productService.getMyProducts(1, 10);

            assertThat(result.getRecords()).hasSize(2);
            assertThat(result.getRecords().get(0).getSellerNickname()).isEqualTo("我");
        }

        @Test
        @DisplayName("没有发布商品时返回空列表")
        void shouldReturnEmptyWhenNoProducts() {
            when(productMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Product> page = new Page<>(1, 10);
                page.setRecords(Collections.emptyList());
                page.setTotal(0);
                return page;
            });

            User seller = new User();
            seller.setId(SELLER_ID);
            seller.setNickname("我");
            when(userMapper.selectById(SELLER_ID)).thenReturn(seller);

            var result = productService.getMyProducts(1, 10);

            assertThat(result.getRecords()).isEmpty();
        }
    }
}
