package com.ming.campustrade.service.impl;

import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.CategoryAddDTO;
import com.ming.campustrade.dto.CategoryUpdateDTO;
import com.ming.campustrade.entity.Category;
import com.ming.campustrade.mapper.CategoryMapper;
import com.ming.campustrade.vo.CategoryVO;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link CategoryServiceImpl} 的单元测试
 *
 * <p>测试策略：Mock {@link CategoryMapper}，通过反射注入 MyBatis-Plus 父类的 baseMapper 字段。
 * 所有 this.getOne、this.save、this.list 等继承方法最终调用 baseMapper，
 * mock mapper 的行为即可控制整个业务方法的执行路径。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryServiceImpl - 分类服务")
class CategoryServiceImplTest {

    @Mock
    private CategoryMapper categoryMapper;

    private CategoryServiceImpl categoryService;

    @Captor
    private ArgumentCaptor<Category> categoryCaptor;

    @BeforeEach
    void setUp() throws Exception {
        categoryService = new CategoryServiceImpl();
        // MyBatis-Plus 父类 ServiceImpl 的 baseMapper 字段受泛型影响，
        // Mockito @InjectMocks 无法可靠注入，需通过反射手动设置
        Field field = CrudRepository.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(categoryService, categoryMapper);
    }

    // ==================== 测试数据 ====================

    private static final Long CATEGORY_ID = 1L;
    private static final String CATEGORY_NAME = "数码产品";

    private Category createCategory(Long id, String name, Integer sort, Integer status) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setSort(sort);
        category.setStatus(status);
        category.setCreateTime(LocalDateTime.now());
        return category;
    }

    private CategoryAddDTO createAddDTO(String name, String icon, Integer sort) {
        CategoryAddDTO dto = new CategoryAddDTO();
        dto.setName(name);
        dto.setIcon(icon);
        dto.setSort(sort);
        return dto;
    }

    // ==================== 添加分类 ====================

    @Nested
    @DisplayName("addCategory 添加分类")
    class AddCategory {

        @Test
        @DisplayName("成功添加分类，sort 为 null 时默认 0")
        void shouldAddCategorySuccessfully() {
            CategoryAddDTO dto = createAddDTO(CATEGORY_NAME, null, null);
            when(categoryMapper.selectOne(any(), anyBoolean())).thenReturn(null);
            when(categoryMapper.insert(any(Category.class))).thenReturn(1);

            categoryService.addCategory(dto);

            verify(categoryMapper).insert(categoryCaptor.capture());
            Category saved = categoryCaptor.getValue();
            assertThat(saved.getName()).isEqualTo(CATEGORY_NAME);
            assertThat(saved.getSort()).isZero();
            assertThat(saved.getStatus()).isOne();
        }

        @Test
        @DisplayName("成功添加分类，传入了 sort 和 icon")
        void shouldAddCategoryWithAllFields() {
            CategoryAddDTO dto = createAddDTO(CATEGORY_NAME, "icon-url", 99);
            when(categoryMapper.selectOne(any(), anyBoolean())).thenReturn(null);
            when(categoryMapper.insert(any(Category.class))).thenReturn(1);

            categoryService.addCategory(dto);

            verify(categoryMapper).insert(categoryCaptor.capture());
            Category saved = categoryCaptor.getValue();
            assertThat(saved.getIcon()).isEqualTo("icon-url");
            assertThat(saved.getSort()).isEqualTo(99);
        }

        @Test
        @DisplayName("分类名重复时抛出 CATEGORY_ALREADY_EXISTS")
        void shouldThrowWhenNameDuplicated() {
            CategoryAddDTO dto = createAddDTO(CATEGORY_NAME, null, null);
            Category existing = createCategory(1L, CATEGORY_NAME, 0, 1);
            when(categoryMapper.selectOne(any(), anyBoolean())).thenReturn(existing);

            assertThatThrownBy(() -> categoryService.addCategory(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.CATEGORY_ALREADY_EXISTS.getCode());
            verify(categoryMapper, never()).insert(any(Category.class));
        }
    }

    // ==================== 修改分类 ====================

    @Nested
    @DisplayName("updateCategory 修改分类")
    class UpdateCategory {

        @Test
        @DisplayName("成功修改分类名称和排序")
        void shouldUpdateCategorySuccessfully() {
            CategoryUpdateDTO dto = new CategoryUpdateDTO();
            dto.setId(CATEGORY_ID);
            dto.setName("新分类名");
            dto.setSort(50);

            Category original = createCategory(CATEGORY_ID, CATEGORY_NAME, 10, 1);
            when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(original);
            when(categoryMapper.selectOne(any(), anyBoolean())).thenReturn(null);

            categoryService.updateCategory(dto);

            verify(categoryMapper).updateById(categoryCaptor.capture());
            Category updated = categoryCaptor.getValue();
            assertThat(updated.getName()).isEqualTo("新分类名");
            assertThat(updated.getSort()).isEqualTo(50);
            assertThat(updated.getStatus()).isOne();
        }

        @Test
        @DisplayName("分类不存在时抛出 CATEGORY_NOT_FOUND")
        void shouldThrowWhenNotFound() {
            CategoryUpdateDTO dto = new CategoryUpdateDTO();
            dto.setId(999L);
            dto.setName("不存在");

            when(categoryMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> categoryService.updateCategory(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.CATEGORY_NOT_FOUND.getCode());
            verify(categoryMapper, never()).updateById(any(Category.class));
        }

        @Test
        @DisplayName("新名称与已有分类重复时抛出 CATEGORY_ALREADY_EXISTS")
        void shouldThrowWhenNameAlreadyUsedByOther() {
            CategoryUpdateDTO dto = new CategoryUpdateDTO();
            dto.setId(CATEGORY_ID);
            dto.setName("同名分类");

            Category original = createCategory(CATEGORY_ID, CATEGORY_NAME, 10, 1);
            Category other = createCategory(2L, "同名分类", 0, 1);
            when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(original);
            when(categoryMapper.selectOne(any(), anyBoolean())).thenReturn(other);

            assertThatThrownBy(() -> categoryService.updateCategory(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.CATEGORY_ALREADY_EXISTS.getCode());
        }

        @Test
        @DisplayName("只改 icon 不变名称时，不检查名称唯一性")
        void shouldSkipNameUniquenessCheckWhenNameNotChanged() {
            CategoryUpdateDTO dto = new CategoryUpdateDTO();
            dto.setId(CATEGORY_ID);
            dto.setName(CATEGORY_NAME);
            dto.setIcon("new-icon.png");

            Category original = createCategory(CATEGORY_ID, CATEGORY_NAME, 10, 1);
            when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(original);

            categoryService.updateCategory(dto);

            verify(categoryMapper, times(1)).selectById(anyLong());
            verify(categoryMapper, times(1)).updateById(any(Category.class));
        }
    }

    // ==================== 删除分类 ====================

    @Nested
    @DisplayName("deleteCategory 删除分类")
    class DeleteCategory {

        @Test
        @DisplayName("成功删除分类（逻辑删除）")
        void shouldDeleteCategorySuccessfully() {
            when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(createCategory(CATEGORY_ID, CATEGORY_NAME, 0, 1));

            categoryService.deleteCategory(CATEGORY_ID);

            verify(categoryMapper).deleteById(any(Category.class));
        }

        @Test
        @DisplayName("删除不存在的分类时抛出 CATEGORY_NOT_FOUND")
        void shouldThrowWhenCategoryNotFound() {
            when(categoryMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> categoryService.deleteCategory(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.CATEGORY_NOT_FOUND.getCode());
            verify(categoryMapper, never()).deleteById(any(Category.class));
        }
    }

    // ==================== 查询分类列表 ====================

    @Nested
    @DisplayName("getCategoryList 查询分类列表")
    class GetCategoryList {

        @Test
        @DisplayName("成功返回启用的分类列表，按 sort 倒序")
        void shouldReturnCategoryList() {
            Category cat1 = createCategory(1L, "A", 100, 1);
            Category cat2 = createCategory(2L, "B", 50, 1);
            when(categoryMapper.selectList(any())).thenReturn(List.of(cat1, cat2));

            List<CategoryVO> result = categoryService.getCategoryList();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("A");
            assertThat(result.get(1).getName()).isEqualTo("B");
        }

        @Test
        @DisplayName("没有分类时返回空列表")
        void shouldReturnEmptyListWhenNoCategories() {
            when(categoryMapper.selectList(any())).thenReturn(Collections.emptyList());

            List<CategoryVO> result = categoryService.getCategoryList();

            assertThat(result).isEmpty();
        }
    }

    // ==================== 查询分类详情 ====================

    @Nested
    @DisplayName("getCategory 查询分类详情")
    class GetCategory {

        @Test
        @DisplayName("成功返回分类 VO")
        void shouldReturnCategory() {
            Category category = createCategory(CATEGORY_ID, CATEGORY_NAME, 10, 1);
            when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category);

            CategoryVO vo = categoryService.getCategory(CATEGORY_ID);

            assertThat(vo.getId()).isEqualTo(CATEGORY_ID);
            assertThat(vo.getName()).isEqualTo(CATEGORY_NAME);
            assertThat(vo.getSort()).isEqualTo(10);
            assertThat(vo.getStatus()).isOne();
        }

        @Test
        @DisplayName("查询不存在的分类时抛出 CATEGORY_NOT_FOUND")
        void shouldThrowWhenCategoryNotFound() {
            when(categoryMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> categoryService.getCategory(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.CATEGORY_NOT_FOUND.getCode());
        }
    }
}
