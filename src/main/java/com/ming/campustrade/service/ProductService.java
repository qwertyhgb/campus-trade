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

    /**
     * 管理员审核商品（通过上架 / 不通过下架）
     *
     * @param id       商品 ID
     * @param approved true=审核通过（上架），false=审核不通过（下架）
     * @param remark   审核备注（驳回原因，通过时可为空）
     */
    void reviewProduct(Long id, boolean approved, String remark);

    /**
     * 管理员查询商品列表（分页，可按状态筛选，含待审核商品）
     *
     * @param status   商品状态筛选（null 表示查全部状态）
     * @param pageNo   页码
     * @param pageSize 每页条数
     */
    IPage<ProductVO> listProductsForAdmin(Integer status, Integer pageNo, Integer pageSize);
}
