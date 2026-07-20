package com.ming.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.entity.Favorite;
import com.ming.campustrade.vo.FavoriteVO;

/**
 * 收藏模块 - 业务接口
 *
 * 继承 IService<Favorite> 后，自动拥有 MyBatis-Plus 提供的通用 CRUD 能力
 * （save、remove、getById、page、exists 等），下面只定义收藏模块特有的业务方法。
 */
public interface FavoriteService extends IService<Favorite> {

    /**
     * 添加收藏（幂等：重复收藏不报错）
     *
     * @param productId 要收藏的商品 ID
     */
    void addFavorite(Long productId);

    /**
     * 取消收藏（物理删除）
     *
     * @param productId 要取消收藏的商品 ID
     */
    void removeFavorite(Long productId);

    /**
     * 判断当前用户是否已收藏指定商品
     *
     * @param productId 商品 ID
     * @return true = 已收藏，false = 未收藏
     */
    boolean isFavorited(Long productId);

    /**
     * 分页查询当前用户的收藏列表
     *
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 包含 FavoriteVO 的分页对象
     */
    IPage<FavoriteVO> getMyFavorites(Integer pageNo, Integer pageSize);
}
