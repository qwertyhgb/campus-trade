package com.ming.campustrade.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.entity.Favorite;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.mapper.FavoriteMapper;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link FavoriteServiceImpl} 的单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteServiceImpl - 收藏服务")
class FavoriteServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private FavoriteMapper favoriteMapper;

    private FavoriteServiceImpl favoriteService;

    @Captor
    private ArgumentCaptor<Favorite> favoriteCaptor;

    private static final Long USER_ID = 100L;
    private static final Long PRODUCT_ID = 1L;

    @BeforeEach
    void setUp() throws Exception {
        UserVO currentUser = new UserVO();
        currentUser.setId(USER_ID);
        UserHolder.saveUser(currentUser);

        favoriteService = new FavoriteServiceImpl(productMapper, userMapper);
        Field field = CrudRepository.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(favoriteService, favoriteMapper);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private Product onSaleProduct() {
        Product p = new Product();
        p.setId(PRODUCT_ID);
        p.setTitle("测试商品");
        p.setStatus(1);
        return p;
    }

    // ==================== 添加收藏 ====================

    @Nested
    @DisplayName("addFavorite 添加收藏")
    class AddFavorite {

        @Test
        @DisplayName("成功添加收藏")
        void shouldAddFavoriteSuccessfully() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(onSaleProduct());
            when(favoriteMapper.exists(any())).thenReturn(false);
            when(favoriteMapper.insert(any(Favorite.class))).thenReturn(1);

            favoriteService.addFavorite(PRODUCT_ID);

            verify(favoriteMapper).insert(favoriteCaptor.capture());
            assertThat(favoriteCaptor.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(favoriteCaptor.getValue().getProductId()).isEqualTo(PRODUCT_ID);
        }

        @Test
        @DisplayName("商品不存在时抛出 PRODUCT_NOT_FOUND")
        void shouldThrowWhenProductNotFound() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

            assertThatThrownBy(() -> favoriteService.addFavorite(PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.PRODUCT_NOT_FOUND.getCode());
            verify(favoriteMapper, never()).insert(any(Favorite.class));
        }

        @Test
        @DisplayName("已收藏过时幂等返回，不抛异常也不执行 INSERT")
        void shouldReturnSilentlyWhenAlreadyFavorited() {
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(onSaleProduct());
            when(favoriteMapper.exists(any())).thenReturn(true);

            favoriteService.addFavorite(PRODUCT_ID);

            verify(favoriteMapper, never()).insert(any(Favorite.class));
        }
    }

    // ==================== 取消收藏 ====================

    @Nested
    @DisplayName("removeFavorite 取消收藏")
    class RemoveFavorite {

        @Test
        @DisplayName("成功取消收藏")
        void shouldRemoveFavoriteSuccessfully() {
            when(favoriteMapper.delete(any())).thenReturn(1);

            favoriteService.removeFavorite(PRODUCT_ID);

            verify(favoriteMapper).delete(any());
        }

        @Test
        @DisplayName("未收藏时抛出 FAVORITE_NOT_FOUND")
        void shouldThrowWhenNotFavorited() {
            when(favoriteMapper.delete(any())).thenReturn(0);

            assertThatThrownBy(() -> favoriteService.removeFavorite(PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.FAVORITE_NOT_FOUND.getCode());
        }
    }

    // ==================== 查询收藏状态 ====================

    @Nested
    @DisplayName("isFavorited 查询收藏状态")
    class IsFavorited {

        @Test
        @DisplayName("已收藏返回 true")
        void shouldReturnTrueWhenFavorited() {
            when(favoriteMapper.exists(any())).thenReturn(true);

            boolean result = favoriteService.isFavorited(PRODUCT_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("未收藏返回 false")
        void shouldReturnFalseWhenNotFavorited() {
            when(favoriteMapper.exists(any())).thenReturn(false);

            boolean result = favoriteService.isFavorited(PRODUCT_ID);

            assertThat(result).isFalse();
        }
    }

    // ==================== 查询收藏列表 ====================

    @Nested
    @DisplayName("getMyFavorites 查询收藏列表")
    class GetMyFavorites {

        @Test
        @DisplayName("成功返回分页收藏列表")
        void shouldReturnFavoritesPage() {
            // 返回空分页（无收藏记录时则不触发后续的 selectByIds）
            when(favoriteMapper.selectPage(any(Page.class), any())).then(invocation -> {
                Page<Favorite> emptyPage = new Page<>(1, 10);
                emptyPage.setRecords(Collections.emptyList());
                return emptyPage;
            });

            var page = favoriteService.getMyFavorites(1, 10);

            assertThat(page).isNotNull();
            assertThat(page.getRecords()).isEmpty();
        }
    }
}
