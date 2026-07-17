package com.ming.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.ProductPublishDTO;
import com.ming.campustrade.dto.ProductQueryDTO;
import com.ming.campustrade.dto.ProductUpdateDTO;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.vo.ProductVO;

public interface ProductService extends IService<Product> {

    /**
     * 发布商品
     */
    void publishProduct(ProductPublishDTO dto);

    /**
     * 修改商品（仅本人）
     */
    void updateProduct(Long id, ProductUpdateDTO dto);

    /**
     * 删除商品（仅本人，逻辑删除）
     */
    void deleteProduct(Long id);

    /**
     * 查看商品详情（浏览量+1）
     */
    ProductVO getProductById(Long id);

    /**
     * 商品列表（分页 + 筛选 + 排序，只查在售商品）
     */
    IPage<ProductVO> listProducts(ProductQueryDTO query);

    /**
     * 修改商品状态（上架/下架/标记已售）
     */
    void updateStatus(Long id, Integer status);

    /**
     * 查询我发布的所有商品
     */
    IPage<ProductVO> getMyProducts(Integer pageNo, Integer pageSize);
}
