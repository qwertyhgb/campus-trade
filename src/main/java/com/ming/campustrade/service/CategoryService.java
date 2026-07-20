package com.ming.campustrade.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.CategoryAddDTO;
import com.ming.campustrade.dto.CategoryUpdateDTO;
import com.ming.campustrade.entity.Category;
import com.ming.campustrade.vo.CategoryVO;

/**
 * 商品分类业务逻辑接口（Service 层）。
 *
 * <p>负责分类的增删改查业务编排。继承 MyBatis-Plus 的 {@link IService} 后，
 * 自动获得针对 {@link Category} 的通用 CRUD 能力（{@code save}、{@code removeById}、
 * {@code getById}、{@code list} 等），因此这里只声明本项目特有的业务方法。</p>
 *
 * @author ming
 */
public interface CategoryService extends IService<Category> {

    /**
     * 新增商品分类。
     *
     * @param categoryAddDTO 新增分类参数
     */
    void addCategory(CategoryAddDTO categoryAddDTO);

    /**
     * 更新商品分类（含启用/禁用状态切换）。
     *
     * @param categoryUpdateDTO 更新分类参数（必须携带分类 ID）
     */
    void updateCategory(CategoryUpdateDTO categoryUpdateDTO);

    /**
     * 删除商品分类（逻辑删除）。
     *
     * @param id 分类主键 ID
     */
    void deleteCategory(Long id);

    /**
     * 查询全部分类列表（通常按 sort 倒序排列）。
     *
     * @return 分类视图对象列表
     */
    List<CategoryVO> getCategoryList();

    /**
     * 根据 ID 查询单个分类。
     *
     * @param id 分类主键 ID
     * @return 分类视图对象
     */
    CategoryVO getCategory(Long id);
}
