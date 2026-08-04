package com.ming.campustrade.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.dto.CommentAddDTO;
import com.ming.campustrade.service.CommentService;
import com.ming.campustrade.vo.CommentVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;

/**
 * 商品留言控制器 —— 处理留言的发表、删除、查询等 HTTP 请求。
 *
 * <h2>核心注解说明</h2>
 * <ul>
 *   <li>{@code @RestController}：等价于 {@code @Controller + @ResponseBody}，
 *       方法返回值自动序列化为 JSON 写入响应体，是 RESTful API 的标准写法。</li>
 *   <li>{@code @RequestMapping("/comment")}：为该控制器下所有接口设置基础路径前缀。
 *       例如方法上映射 {@code @PostMapping("/add")}，最终完整路径就是 {@code POST /comment/add}。</li>
 *   <li>{@code @Tag}：Swagger/Knife4j 注解，用于在接口文档中对接口分组。</li>
 * </ul>
 *
 * <h2>权限约定</h2>
 * <ul>
 *   <li>浏览商品留言和查看回复由 {@code SecurityConfig} 配置为公开接口</li>
 *   <li>其他接口默认需要登录，如发表、删除留言</li>
 * </ul>
 *
 * @author ming
 */
@Slf4j
@Tag(name = "商品留言管理", description = "商品留言的发表、删除、查询顶级留言、查询回复等操作")
@RestController
@RequestMapping("/comment")
@Validated // 启用方法参数（@RequestParam/@PathVariable）上的约束校验（如 @Min/@Max）
public class CommentController {

    private final CommentService commentService;

    /**
     * 构造器注入：Spring 启动时自动把 CommentService 的实现类实例传进来。
     * 因为只有一个构造器，所以不需要额外加 @Autowired 注解（Spring 4.3+ 特性）。
     */
    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 发表留言或回复（需要登录）。
     *
     * <p>{@code @RequestBody} 从请求体读取 JSON 并反序列化为 CommentAddDTO 对象。</p>
     * <p>{@code @Valid} 触发 Jakarta Validation 校验：DTO 上的 @NotBlank、@NotNull、@Size
     * 等注解会在此处自动生效，校验不通过直接返回 400 错误，不会进入方法体。</p>
     *
     * <p>parentId 为 null 时发表顶级留言，非 null 时发表回复。</p>
     *
     * @param commentAddDTO 留言信息（商品ID、内容、可选的父留言ID和被回复者ID）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "发表留言", description = "对商品发表留言或回复，parentId 为空时是顶级留言，非空时是回复")
    @PostMapping("/add")
    public Result<Void> add(@RequestBody @Valid CommentAddDTO commentAddDTO) {
        log.info("发表留言：productId={}, parentId={}", commentAddDTO.getProductId(), commentAddDTO.getParentId());
        commentService.addComment(commentAddDTO);
        return Result.success();
    }

    /**
     * 删除留言（需要登录，且只能删除自己的留言）。
     *
     * <p>这里是「逻辑删除」：数据库记录并不会真正被 DELETE，
     * 而是把 deleted 字段标记为 1，查询时自动过滤。</p>
     *
     * @param id 留言ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "删除留言", description = "删除自己发表的留言（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "留言ID") @PathVariable Long id) {
        log.info("删除留言：commentId={}", id);
        commentService.deleteComment(id);
        return Result.success();
    }

    /**
     * 查询商品下的顶级留言列表（公开接口，无需登录）。
     *
     * <p>SecurityConfig 将此接口配置为公开接口，
     * 因为未登录用户也应该能浏览商品留言（电商基本体验）。</p>
     *
     * <p>只返回顶级留言（parentId 为 null），回复列表由 /{id}/replies 单独加载。</p>
     *
     * @param productId 商品ID（查询参数）
     * @param pageNo    页码，从1开始（查询参数，默认1）
     * @param pageSize  每页条数（查询参数，默认10）
     * @return 分页的顶级留言列表
     */
    @Operation(summary = "商品留言列表", description = "分页查询商品下的顶级留言列表（公开接口），按时间正序")
    @GetMapping("/product/{productId}")
    public Result<IPage<CommentVO>> listByProduct(@Parameter(description = "商品ID") @PathVariable Long productId,
                                                  @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNo,
                                                  @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        log.info("查询商品留言列表：productId={}, pageNo={}, pageSize={}", productId, pageNo, pageSize);
        return Result.success(commentService.getCommentsByProduct(productId, pageNo, pageSize));
    }

    /**
     * 查询某条留言的回复列表（公开接口，无需登录）。
     *
     * <p>使用场景：用户点击顶级留言下方的"展开回复"时，前端按 parentId 加载回复。</p>
     *
     * @param parentId 父留言ID（路径变量）
     * @param pageNo   页码，从1开始（查询参数，默认1）
     * @param pageSize 每页条数（查询参数，默认10）
     * @return 分页的回复列表
     */
    @Operation(summary = "留言回复列表", description = "分页查询某条留言下的所有回复（公开接口），按时间正序")
    @GetMapping("/{parentId}/replies")
    public Result<IPage<CommentVO>> listReplies(@Parameter(description = "父留言ID") @PathVariable Long parentId,
                                                @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNo,
                                                @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        log.info("查询留言回复列表：parentId={}, pageNo={}, pageSize={}", parentId, pageNo, pageSize);
        return Result.success(commentService.getReplies(parentId, pageNo, pageSize));
    }

    /**
     * 我的留言列表（需要登录）。
     *
     * <p>查看当前登录用户发表的全部留言（含顶级留言和回复），分页返回，按时间倒序。</p>
     *
     * @param pageNo   页码，从1开始（查询参数，默认1）
     * @param pageSize 每页条数（查询参数，默认10）
     * @return 分页的我的留言列表
     */
    @Operation(summary = "我的留言列表", description = "分页查询当前登录用户发表的全部留言，按时间倒序")
    @GetMapping("/my")
    public Result<IPage<CommentVO>> myComments(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNo,
                                               @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer pageSize) {
        log.info("查询我的留言列表：pageNo={}, pageSize={}", pageNo, pageSize);
        return Result.success(commentService.getMyComments(pageNo, pageSize));
    }
}
