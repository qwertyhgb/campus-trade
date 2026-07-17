package com.ming.campustrade.service.impl;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.CategoryAddDTO;
import com.ming.campustrade.dto.CategoryUpdateDTO;
import com.ming.campustrade.entity.Category;
import com.ming.campustrade.mapper.CategoryMapper;
import com.ming.campustrade.service.CategoryService;
import com.ming.campustrade.vo.CategoryVO;

@Slf4j
@Service
@SuppressWarnings("null")
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    @Override
    public void addCategory(CategoryAddDTO categoryAddDTO) {
        log.info("添加分类：name={}", categoryAddDTO.getName());
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getName, categoryAddDTO.getName());
        Category existCategory = this.getOne(wrapper);
        if (existCategory != null) {
            throw new BusinessException(ResultCode.CATEGORY_ALREADY_EXISTS);
        }

        Category category = new Category();
        category.setName(categoryAddDTO.getName());
        category.setIcon(categoryAddDTO.getIcon());
        category.setSort(categoryAddDTO.getSort() != null ? categoryAddDTO.getSort() : 0);
        category.setStatus(1);

        this.save(category);
        log.info("添加分类成功：categoryId={}", category.getId());
    }

    @Override
    public void updateCategory(CategoryUpdateDTO categoryUpdateDTO) {
        log.info("修改分类：categoryId={}, name={}", categoryUpdateDTO.getId(), categoryUpdateDTO.getName());
        Category category = this.getById(categoryUpdateDTO.getId());
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }

        if (!category.getName().equals(categoryUpdateDTO.getName())) {
            LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Category::getName, categoryUpdateDTO.getName());
            Category exisCategory = this.getOne(wrapper);
            if (exisCategory != null) {
                throw new BusinessException(ResultCode.CATEGORY_ALREADY_EXISTS);
            }
        }

        category.setName(categoryUpdateDTO.getName());
        category.setIcon(categoryUpdateDTO.getIcon());
        category.setSort(categoryUpdateDTO.getSort());
        category.setStatus(categoryUpdateDTO.getStatus());

        this.updateById(category);
        log.info("修改分类成功：categoryId={}", category.getId());
    }

    @Override
    public void deleteCategory(Long id) {
        log.info("删除分类：categoryId={}", id);
        Category category = this.getById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }

        this.removeById(category);
        log.info("删除分类成功：categoryId={}", id);
    }

    @Override
    public List<CategoryVO> getCategoryList() {
        log.info("查询分类列表");
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getStatus, 1).orderByDesc(Category::getSort);

        List<Category> categories = this.list(wrapper);

        return categories.stream().map(category -> convertToCategoryVO(category)).toList();
    }

    @Override
    public CategoryVO getCategory(Long id) {
        log.info("查询分类详情：categoryId={}", id);
        Category category = this.getById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.CATEGORY_NOT_FOUND);
        }

        return convertToCategoryVO(category);
    }
    
    private static CategoryVO convertToCategoryVO(Category category) {
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setIcon(category.getIcon());
        vo.setSort(category.getSort());
        vo.setStatus(category.getStatus());
        vo.setCreateTime(category.getCreateTime());
        return vo;
    }

}
