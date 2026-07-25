package com.ming.campustrade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.CommentAddDTO;
import com.ming.campustrade.entity.Comment;
import com.ming.campustrade.vo.CommentVO;

/**
 * 商品留言服务接口
 *
 * <p>定义留言模块的业务方法，包括：发表留言/回复、删除留言、
 * 查询商品下的顶级留言列表、查询某条留言的回复列表、查询我的留言。</p>
 *
 * <p>继承 {@link IService} 后自动拥有 MyBatis-Plus 提供的通用 CRUD 方法
 * （save、getById、removeById、page 等），本接口只需声明自定义的业务方法。</p>
 */
public interface CommentService extends IService<Comment> {

    /**
     * 发表留言或回复（parentId 为 null 时是顶级留言，非 null 时是回复）
     */
    void addComment(CommentAddDTO commentAddDTO);

    /**
     * 删除留言（仅本人，逻辑删除）
     */
    void deleteComment(Long id);

    /**
     * 查询商品下的顶级留言列表（分页，只查 parentId 为 null 的顶级留言）
     */
    IPage<CommentVO> getCommentsByProduct(Long productId, Integer pageNo, Integer pageSize);

    /**
     * 查询某条留言的所有回复（分页，查 parentId 等于指定值的回复）
     */
    IPage<CommentVO> getReplies(Long parentId, Integer pageNo, Integer pageSize);

    /**
     * 查询我发表的所有留言（分页，包含顶级留言和回复）
     */
    IPage<CommentVO> getMyComments(Integer pageNo, Integer pageSize);
}
