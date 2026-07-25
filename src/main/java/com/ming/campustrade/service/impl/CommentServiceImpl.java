package com.ming.campustrade.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.CommentAddDTO;
import com.ming.campustrade.entity.Comment;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.CommentMapper;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.service.CommentService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.CommentVO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@SuppressWarnings("null") // 抑制 MyBatis-Plus Lambda 方法引用与空类型分析冲突的误报警告
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    private final ProductMapper productMapper;
    private final UserMapper userMapper;

    public CommentServiceImpl(ProductMapper productMapper, UserMapper userMapper) {
        this.productMapper = productMapper;
        this.userMapper = userMapper;
    }

    /**
     * 发表留言或回复
     *
     * <p><b>整体流程（4 步）：</b></p>
     * <ol>
     *   <li>获取当前登录用户 ID（从 ThreadLocal，不可伪造）</li>
     *   <li>校验商品是否存在（不能给不存在的商品留言）</li>
     *   <li>如果是回复（parentId 非空），校验父留言合法性</li>
     *   <li>构建 Comment 实体并保存到数据库</li>
     * </ol>
     *
     * <p><b>回复时的校验逻辑（第 3 步细节）：</b></p>
     * <ul>
     *   <li>父留言必须存在（防止传入不存在的 parentId）</li>
     *   <li>父留言必须属于同一商品（防止跨商品挂载回复，导致数据错乱）</li>
     *   <li>强制两级结构：如果父留言本身已经是回复（它的 parentId 非空），
     *       则自动将新回复挂到"根留言"下，避免产生无限嵌套层级</li>
     *   <li>replyToUserId 校验：不能回复自己；如果前端没传则自动填充父留言的作者</li>
     * </ul>
     *
     * <p><b>什么是"两级结构"？</b><br>
     * 商品留言区通常采用"顶级留言 + 平铺回复"的两级模式（类似B站评论区）：
     * 第一级是直接对商品发表的留言（parentId=null），
     * 第二级是对某条顶级留言的回复（parentId=顶级留言ID），所有回复平铺展示。
     * 不允许"回复的回复"再嵌套第三级，否则前端展示和查询都会变得复杂。</p>
     *
     * @param commentAddDTO 发表留言请求参数（商品ID、内容、可选的父留言ID和被回复者ID）
     */
    @Override
    public void addComment(CommentAddDTO commentAddDTO) {
        // ===== 第 1 步：获取当前登录用户 ID =====
        // 从 ThreadLocal 中取出（由 LoginInterceptor 在请求进入时注入），
        // 而不是从前端参数传入——防止用户伪造 userId 冒充他人发言。
        Long userId = UserHolder.getUserVO().getId();

        // ===== 第 2 步：校验商品是否存在 =====
        // 不能给已删除或不存在的商品留言。
        // selectById 会自动追加 WHERE deleted=0（MyBatis-Plus 逻辑删除），已删除的商品查不到。
        Product product = productMapper.selectById(commentAddDTO.getProductId());
        if (product == null) {
            log.warn("发表留言失败，商品不存在：userId={}, productId={}", userId, commentAddDTO.getProductId());
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // ===== 第 3 步：如果是回复，校验父留言的合法性 =====
        // parentId 为 null → 顶级留言，无需校验
        // parentId 非 null → 这是一条回复，需要做以下 4 项校验
        Long parentId = commentAddDTO.getParentId();
        Long replyToUserId = commentAddDTO.getReplyToUserId();

        if (parentId != null) {
            // 3a. 父留言必须存在
            Comment parentComment = this.getById(parentId);
            if (parentComment == null) {
                log.warn("发表回复失败，父留言不存在：userId={}, parentId={}", userId, parentId);
                throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
            }

            // 3b. 父留言必须属于同一商品
            // 防止恶意用户传入其他商品的留言 ID 作为 parentId，
            // 导致回复"跨商品挂载"，查询时数据错乱。
            if (!parentComment.getProductId().equals(commentAddDTO.getProductId())) {
                log.warn("发表回复失败，父留言不属于该商品：userId={}, parentId={}, parentProductId={}, targetProductId={}",
                        userId, parentId, parentComment.getProductId(), commentAddDTO.getProductId());
                throw new BusinessException(ResultCode.BAD_REQUEST, "父留言不属于该商品");
            }

            // 3c. 强制两级结构：如果父留言本身已经是回复（它的 parentId 非空），
            // 说明用户在对"回复"再回复。为了保持两级结构，
            // 自动将新回复挂到根留言（顶级留言）下，而不是嵌套第三层。
            // 例如：A 发表顶级留言 → B 回复 A → C 回复 B
            //   C 的回复 parentId 应该是 A 的留言 ID（根），而不是 B 的回复 ID
            if (parentComment.getParentId() != null) {
                parentId = parentComment.getParentId();
            }

            // 3d. 处理 replyToUserId（被回复者）
            if (replyToUserId == null) {
                // 前端没传被回复者 → 默认回复父留言的作者
                // 场景：用户点击"回复"按钮直接回复顶级留言
                replyToUserId = parentComment.getUserId();
            }

            // 不能回复自己（无意义操作，通常是前端 bug）
            if (replyToUserId.equals(userId)) {
                replyToUserId = null; // 置空，不显示 "回复 @自己"
            }
        }

        // ===== 第 4 步：构建 Comment 实体并保存 =====
        Comment comment = new Comment();
        comment.setProductId(commentAddDTO.getProductId());  // 商品 ID
        comment.setUserId(userId);                           // 留言者（来自登录态，不可伪造）
        comment.setContent(commentAddDTO.getContent());      // 留言内容（已通过 @Valid 校验非空和长度）
        comment.setParentId(parentId);                       // 父留言 ID（null=顶级留言）
        comment.setReplyToUserId(replyToUserId);             // 被回复者 ID（null=非回复或回复顶级留言作者）

        // this.save() 继承自 ServiceImpl，内部执行 INSERT INTO comment (...) VALUES (...)
        // 保存成功后，MyBatis-Plus 自动把数据库生成的自增 ID 回填到 comment.getId()
        this.save(comment);
        log.info("发表留言成功：commentId={}, userId={}, productId={}, parentId={}",
                comment.getId(), userId, commentAddDTO.getProductId(), parentId);
    }

    /**
     * 删除留言（仅本人可操作，逻辑删除）
     *
     * <p><b>流程：</b>查留言是否存在 → 校验是否本人 → 逻辑删除</p>
     *
     * <p><b>逻辑删除 vs 物理删除：</b></p>
     * <ul>
     *   <li><b>物理删除</b>（DELETE FROM comment WHERE id=?）：数据真的从数据库消失，不可恢复</li>
     *   <li><b>逻辑删除</b>（UPDATE comment SET deleted=1 WHERE id=?）：只是标记 deleted=1，数据还在</li>
     * </ul>
     * <p>留言采用逻辑删除，因为留言可能涉及交易纠纷举证，需要保留历史记录可追溯。
     * Comment 实体的 deleted 字段标注了 @TableLogic，MyBatis-Plus 会自动把
     * removeById 的 DELETE 改写成 UPDATE SET deleted=1，后续查询也会自动过滤已删除记录。</p>
     *
     * <p><b>注意：</b>删除顶级留言时，其下的所有回复并不会被级联删除。
     * 前端展示时通过 parentId 关联查询，如果父留言已删除（deleted=1），
     * 回复仍然可见但会显示"原留言已删除"。这是常见社区平台的做法（如知乎、B站）。</p>
     *
     * @param id 留言 ID
     * @throws BusinessException 留言不存在时抛出 COMMENT_NOT_FOUND，非本人时抛出 CANNOT_DELETE_OTHERS_COMMENT
     */
    @Override
    public void deleteComment(Long id) {
        log.info("删除留言：commentId={}", id);

        // 1. 从 ThreadLocal 获取当前登录用户 ID（由拦截器注入，不可伪造）
        Long userId = UserHolder.getUserVO().getId();

        // 2. 根据 ID 查询留言（MyBatis-Plus 自动加 WHERE deleted=0，已删除的查不到）
        Comment comment = this.getById(id);
        if (comment == null) {
            log.warn("删除留言失败，留言不存在：commentId={}, userId={}", id, userId);
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }

        // 3. 校验权限：只有留言的作者本人才能删除
        // 用 .equals() 而不是 ==，因为 Long 是包装类型，== 比较的是引用地址
        if (!userId.equals(comment.getUserId())) {
            log.warn("删除留言失败，无权操作他人留言：commentId={}, userId={}, commentAuthorId={}",
                    id, userId, comment.getUserId());
            throw new BusinessException(ResultCode.CANNOT_DELETE_OTHERS_COMMENT);
        }

        // 4. 逻辑删除（@TableLogic 自动把 DELETE 转成 UPDATE SET deleted=1）
        this.removeById(comment);
        log.info("删除留言成功：commentId={}, userId={}", id, userId);
    }

    /**
     * 查询商品下的顶级留言列表（分页，含留言者和被回复者信息）
     *
     * <p><b>整体流程（5 步）：</b></p>
     * <ol>
     *   <li>构建查询条件：只查该商品的<b>顶级留言</b>（parentId IS NULL），按时间正序</li>
     *   <li>分页查询留言</li>
     *   <li>收集所有涉及的用户 ID（留言者 + 被回复者），批量查询用户信息（避免 N+1）</li>
     *   <li>把每条 Comment 转换为 CommentVO（拼上昵称、头像）</li>
     *   <li>组装分页返回对象</li>
     * </ol>
     *
     * <p><b>为什么只查顶级留言（parentId IS NULL）？</b><br>
     * 留言区采用"顶级留言 + 平铺回复"的两级结构。这个接口负责商品详情页的
     * 主留言列表，只展示顶级留言；每条顶级留言下的回复由 {@link #getReplies} 单独按需加载
     * （用户点击"展开回复"时才查）。如果这里把回复也查出来，会导致列表层级混乱。</p>
     *
     * <p><b>什么是 N+1 查询问题？如何避免？</b><br>
     * 假设一页 10 条留言来自 8 个不同用户。如果对每条留言单独查一次用户信息，
     * 就是 1（查留言）+ N（逐条查用户）次 SQL。我们的做法：先把所有用户 ID
     * （留言者 + 被回复者）收集去重，用 selectByIds 一次性批量查出，再放进 Map
     * 供 O(1) 查找，总共只需 2 次 SQL。</p>
     *
     * <p><b>为什么用 Set 收集用户 ID？</b><br>
     * Set 自动去重。同一个用户可能发了多条留言，或既是留言者又是被回复者，
     * 用 Set 能避免重复的 ID，减少批量查询的数据量。</p>
     *
     * @param productId 商品 ID
     * @param pageNo    页码（从 1 开始）
     * @param pageSize  每页条数
     * @return 分页的顶级留言列表（VO，含留言者昵称/头像、被回复者昵称）
     */
    @Override
    public IPage<CommentVO> getCommentsByProduct(Long productId, Integer pageNo, Integer pageSize) {
        log.info("查询商品留言列表：productId={}, pageNo={}, pageSize={}", productId, pageNo, pageSize);

        // ===== 第 1 步：构建查询条件 =====
        // 只查该商品的"顶级留言"：product_id=? AND parent_id IS NULL
        // 按创建时间正序（先发的在前，符合留言区从上到下的阅读习惯）
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getProductId, productId)
                .isNull(Comment::getParentId)          // 关键：只查顶级留言，回复由 getReplies 单独加载
                .orderByAsc(Comment::getCreateTime);

        // ===== 第 2 步：分页查询 =====
        Page<Comment> page = new Page<>(pageNo, pageSize);
        Page<Comment> commentPage = this.page(page, wrapper);

        // 当前页没有留言 → 直接返回空分页对象，避免后续无意义的批量查询
        if (commentPage.getRecords().isEmpty()) {
            Page<CommentVO> emptyPage = new Page<>();
            emptyPage.setTotal(0);
            emptyPage.setCurrent(commentPage.getCurrent());
            emptyPage.setSize(commentPage.getSize());
            return emptyPage;
        }

        // ===== 第 3 步：收集用户 ID 并批量查询（避免 N+1）=====
        // 用 Set 自动去重：留言者、被回复者可能重复，去重后减少查询量
        Set<Long> userIds = new HashSet<>();
        for (Comment c : commentPage.getRecords()) {
            userIds.add(c.getUserId());                 // 留言者
            if (c.getReplyToUserId() != null) {
                userIds.add(c.getReplyToUserId());      // 被回复者（回复顶级留言作者时才有）
            }
        }

        // 批量查询用户，转成 Map<用户ID, User> 供后续 O(1) 查找
        // userIds 理论上不会为空（至少有留言者），但仍加判空保护，返回不可变空 Map 更省内存
        Map<Long, User> userMap = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));

        // ===== 第 4 步：转换为 VO 列表 =====
        List<CommentVO> voList = commentPage.getRecords().stream()
                .map(c -> convertToCommentVO(c, userMap))
                .toList();

        // ===== 第 5 步：组装分页返回对象 =====
        // 不能直接返回 commentPage，因为它的泛型是 Comment，需要转成 CommentVO
        Page<CommentVO> voPage = new Page<>();
        voPage.setRecords(voList);
        voPage.setTotal(commentPage.getTotal());
        voPage.setCurrent(commentPage.getCurrent());
        voPage.setSize(commentPage.getSize());

        return voPage;
    }

    /**
     * 查询某条留言的所有回复（分页，含留言者和被回复者信息）
     *
     * <p><b>整体流程与 {@link #getCommentsByProduct} 完全一致，只是查询条件不同：</b><br>
     * 这里查的是 parent_id = 指定值的所有回复（而不是 parent_id IS NULL 的顶级留言）。</p>
     *
     * <p><b>使用场景：</b><br>
     * 用户在商品详情页看到一条顶级留言下方显示"共 5 条回复"，点击展开时，
     * 前端调用本接口按 parentId 加载这条留言下的所有回复，平铺展示。</p>
     *
     * <p><b>为什么按时间正序（ASC）？</b><br>
     * 回复通常按"先发的在上"展示，符合对话的时间顺序，方便读者理解上下文。</p>
     *
     * @param parentId 父留言 ID（要查看哪条留言的回复）
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页的回复列表（VO，含留言者昵称/头像、被回复者昵称）
     */
    @Override
    public IPage<CommentVO> getReplies(Long parentId, Integer pageNo, Integer pageSize) {
        log.info("查询留言回复列表：parentId={}, pageNo={}, pageSize={}", parentId, pageNo, pageSize);

        // 构建查询条件：parent_id = 指定值，按时间正序（先发的在前）
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getParentId, parentId)
                .orderByAsc(Comment::getCreateTime);

        return pageAndConvert(wrapper, pageNo, pageSize);
    }

    /**
     * 查询当前登录用户发表的所有留言（分页，含留言者和被回复者信息）
     *
     * <p><b>和其他查询的区别：</b></p>
     * <ul>
     *   <li>getCommentsByProduct：按商品查顶级留言，面向所有访客</li>
     *   <li>getReplies：按父留言查回复，面向所有访客</li>
     *   <li>getMyComments：按当前登录用户查其发表的全部留言（顶级留言 + 回复都算），需要登录</li>
     * </ul>
     *
     * <p><b>使用场景：</b>个人中心的"我的留言"页面，展示用户在各个商品下发表过的所有留言。</p>
     *
     * <p><b>为什么按时间倒序（DESC）？</b><br>
     * "我的留言"是个人历史记录，最新发表的排在最前面，符合"最近动态"的浏览习惯。</p>
     *
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页的我的留言列表（VO）
     */
    @Override
    public IPage<CommentVO> getMyComments(Integer pageNo, Integer pageSize) {
        // 从 ThreadLocal 获取当前登录用户 ID（由拦截器注入，不可伪造）
        Long userId = UserHolder.getUserVO().getId();
        log.info("查询我的留言列表：userId={}, pageNo={}, pageSize={}", userId, pageNo, pageSize);

        // 构建查询条件：user_id = 当前用户，按时间倒序（最新的在前）
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getUserId, userId)
                .orderByDesc(Comment::getCreateTime);

        return pageAndConvert(wrapper, pageNo, pageSize);
    }

    /**
     * 私有辅助方法：执行分页查询 + 批量查用户 + 转换为 VO 分页对象
     *
     * <p><b>为什么抽取这个方法？</b><br>
     * getCommentsByProduct、getReplies、getMyComments 三个方法的差异仅在于
     * 查询条件（WHERE + ORDER BY）不同，后续"分页 → 收集用户 ID → 批量查用户 →
     * 转 VO → 组装分页"的流程完全一样。抽取成公共方法后，调用方只需构建好
     * wrapper 传进来，避免三处重复代码，以后改逻辑也只改一处。</p>
     *
     * <p><b>为什么参数是 wrapper 而不是各种查询条件字段？</b><br>
     * 用 LambdaQueryWrapper 承载查询条件，让本方法与"具体查什么"解耦——
     * 无论是按商品、按父留言还是按用户查询，本方法都不关心，只负责通用的分页转换流程。</p>
     *
     * @param wrapper  已构建好的查询条件（调用方决定查顶级留言/回复/我的留言）
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 转换后的 CommentVO 分页对象
     */
    private IPage<CommentVO> pageAndConvert(LambdaQueryWrapper<Comment> wrapper, Integer pageNo, Integer pageSize) {
        // 1. 分页查询
        Page<Comment> page = new Page<>(pageNo, pageSize);
        Page<Comment> commentPage = this.page(page, wrapper);

        // 2. 当前页没有数据 → 返回空分页对象，避免后续无意义的批量查询
        if (commentPage.getRecords().isEmpty()) {
            Page<CommentVO> emptyPage = new Page<>();
            emptyPage.setTotal(0);
            emptyPage.setCurrent(commentPage.getCurrent());
            emptyPage.setSize(commentPage.getSize());
            return emptyPage;
        }

        // 3. 收集用户 ID（留言者 + 被回复者），用 Set 去重，批量查询避免 N+1
        Set<Long> userIds = new HashSet<>();
        for (Comment c : commentPage.getRecords()) {
            userIds.add(c.getUserId());                 // 留言者
            if (c.getReplyToUserId() != null) {
                userIds.add(c.getReplyToUserId());      // 被回复者
            }
        }
        Map<Long, User> userMap = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));

        // 4. 转换为 VO 列表
        List<CommentVO> voList = commentPage.getRecords().stream()
                .map(c -> convertToCommentVO(c, userMap))
                .toList();

        // 5. 组装分页返回对象
        Page<CommentVO> voPage = new Page<>();
        voPage.setRecords(voList);
        voPage.setTotal(commentPage.getTotal());
        voPage.setCurrent(commentPage.getCurrent());
        voPage.setSize(commentPage.getSize());

        return voPage;
    }
    
    private static CommentVO convertToCommentVO(Comment comment, Map<Long, User> userMap) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setProductId(comment.getProductId());
        vo.setContent(comment.getContent());
        vo.setParentId(comment.getParentId());
        vo.setUserId(comment.getUserId());
        vo.setReplyToUserId(comment.getReplyToUserId());
        vo.setCreateTime(comment.getCreateTime());

        // 拼装留言者信息
        User user = userMap.get(comment.getUserId());
        if (user != null) {
            vo.setUserNickname(user.getNickname());
            vo.setUserAvatar(user.getAvatar());
        }

        // 拼装被回复者信息
        if (comment.getReplyToUserId() != null) {
            User replyToUser = userMap.get(comment.getReplyToUserId());
            if (replyToUser != null) {
                vo.setReplyToNickname(replyToUser.getNickname());
            }
        }

        return vo;
    }
}
