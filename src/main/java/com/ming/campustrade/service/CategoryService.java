package com.ming.campustrade.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.CategoryAddDTO;
import com.ming.campustrade.dto.CategoryUpdateDTO;
import com.ming.campustrade.entity.Category;
import com.ming.campustrade.vo.CategoryVO;

public interface CategoryService extends IService<Category>{
    
    void addCategory(CategoryAddDTO categoryAddDTO);

    void updateCategory(CategoryUpdateDTO categoryUpdateDTO);

    void deleteCategory(Long id);

    List<CategoryVO> getCategoryList();

    CategoryVO getCategory(Long id);
}
