package com.ming.campustrade.controller;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.PublicApi;
import com.ming.campustrade.common.annotation.RequireRole;
import com.ming.campustrade.dto.CategoryAddDTO;
import com.ming.campustrade.dto.CategoryUpdateDTO;
import com.ming.campustrade.service.CategoryService;
import com.ming.campustrade.vo.CategoryVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分类管理控制器 —— 处理商品分类的增删改查等 HTTP 请求。
 *
 * <h2>核心注解说明</h2>
 * <ul>
 *   <li>{@code @RestController}：等价于 {@code @Controller + @ResponseBody}。
 *       标注后，该类中所有方法的返回值都会被自动序列化为 JSON 写入响应体，
 *       不需要在每个方法上单独加 {@code @ResponseBody}。
 *       这是 RESTful API 开发的标准写法。</li>
 *   <li>{@code @RequestMapping("/category")}：为该控制器下所有接口设置「基础路径前缀」。
 *       例如方法上映射 {@code @PostMapping("/add")}，
 *       最终完整路径就是 {@code POST /category/add}。</li>
 *   <li>{@code @Tag}：Swagger/Knife4j 注解，用于在接口文档中对接口进行「分组」。
 *       打开 http://localhost:8080/doc.html 后，左侧导航栏会按 Tag 名称分组显示。</li>
 * </ul>
 *
 * <h2>依赖注入方式</h2>
 * <p>
 * 本类使用「构造器注入」（Constructor Injection）而非 {@code @Autowired} 字段注入。
 * 原因：
 * <ol>
 *   <li>字段可以声明为 {@code final}，保证不可变性，线程安全；</li>
 *   <li>如果忘记注入，启动时就会报错（NullPointerException），而不是运行时才炸；</li>
 *   <li>方便单元测试时直接 new 出来传入 mock 对象。</li>
 * </ol>
 * </p>
 *
 * <h2>权限约定</h2>
 * <ul>
 *   <li>{@code @RequireRole(1)}：增删改操作仅管理员可执行（分类是全局数据，普通用户不能随意修改）</li>
 *   <li>{@code @PublicApi}：查询操作公开（发布商品时前端需要拉取分类下拉列表，此时用户可能尚未登录）</li>
 * </ul>
 *
 * @author Ming
 * @since 1.0.0
 */
@Slf4j
@Tag(name = "分类管理", description = "商品分类的增删改查等操作")
@RestController
@RequestMapping("/category")
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 构造器注入：Spring 启动时自动把 CategoryService 的实现类实例传进来。
     * 因为只有一个构造器，所以不需要额外加 @Autowired 注解（Spring 4.3+ 特性）。
     */
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 添加分类（仅管理员）。
     *
     * <p>{@code @RequireRole(1)} 表示需要管理员权限（role >= 1），
     * 普通用户访问会被 RoleInterceptor 拦截并返回 403。</p>
     * <p>{@code @RequestBody} 从请求体读取 JSON 并反序列化为 CategoryAddDTO 对象。</p>
     * <p>{@code @Valid} 触发 Jakarta Validation 校验：DTO 上的 @NotBlank 等注解
     * 会在此处自动生效，校验不通过直接返回 400 错误，不会进入方法体。</p>
     *
     * @param dto 新分类信息（名称、排序号等）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "添加分类", description = "新增商品分类（仅管理员）")
    @RequireRole(1)
    @PostMapping("/add")
    public Result<Void> add(@RequestBody @Valid CategoryAddDTO dto) {
        log.info("添加分类：name={}", dto.getName());
        categoryService.addCategory(dto);
        log.info("添加分类成功：name={}", dto.getName());
        return Result.success();
    }

    /**
     * 修改分类（仅管理员）。
     *
     * <p>使用 PUT 方法，语义是「更新已有资源」。
     * 与 POST /add 的区别：add 是创建新记录，update 是修改已有记录。</p>
     *
     * @param dto 要更新的分类信息（必须包含 id 字段）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "修改分类", description = "修改商品分类信息（仅管理员）")
    @RequireRole(1)
    @PutMapping("/update")
    public Result<Void> update(@RequestBody @Valid CategoryUpdateDTO dto) {
        log.info("修改分类：categoryId={}, name={}", dto.getId(), dto.getName());
        categoryService.updateCategory(dto);
        log.info("修改分类成功：categoryId={}", dto.getId());
        return Result.success();
    }

    /**
     * 删除分类（仅管理员）。
     *
     * <p>{@code @DeleteMapping("/{id}")} 使用 DELETE 方法 + 路径变量，符合 RESTful 风格：
     * DELETE 语义是「删除指定资源」。</p>
     * <p>{@code @PathVariable} 从 URL 路径中提取分类 ID：
     * 例如请求 DELETE /category/5，则 id = 5。</p>
     * <p>注意：如果该分类下还有商品，Service 层应阻止删除并返回友好提示。</p>
     *
     * @param id 分类ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "删除分类", description = "删除商品分类（仅管理员）")
    @RequireRole(1)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "分类ID") @PathVariable Long id) {
        log.info("删除分类：categoryId={}", id);
        categoryService.deleteCategory(id);
        log.info("删除分类成功：categoryId={}", id);
        return Result.success();
    }

    /**
     * 分类列表（公开接口，无需登录）。
     *
     * <p>{@code @PublicApi} 标记此接口跳过登录拦截器。
     * 为什么公开？因为前端在「发布商品」页面需要拉取分类下拉选项，
     * 如果用户还没登录就想先看看有哪些分类，不应该被拦截。</p>
     *
     * @return 所有分类的列表（通常数据量不大，无需分页）
     */
    @Operation(summary = "分类列表", description = "获取所有商品分类列表（公开接口），发布商品时前端用来拉取分类选项")
    @PublicApi
    @GetMapping("/list")
    public Result<List<CategoryVO>> list() {
        log.info("查询分类列表");
        List<CategoryVO> list = categoryService.getCategoryList();
        log.info("查询分类列表成功，共 {} 条", list.size());
        return Result.success(list);
    }

    /**
     * 查询分类详情（公开接口，无需登录）。
     *
     * <p>{@code @PathVariable} 从 URL 路径中提取分类 ID：
     * 例如请求 GET /category/3，则 id = 3。</p>
     *
     * @param id 分类ID（路径变量）
     * @return 分类详细信息
     */
    @Operation(summary = "查询分类详情", description = "根据分类ID获取分类详细信息（公开接口）")
    @PublicApi
    @GetMapping("/{id}")
    public Result<CategoryVO> getById(@Parameter(description = "分类ID") @PathVariable Long id) {
        log.info("查询分类详情：categoryId={}", id);
        return Result.success(categoryService.getCategory(id));
    }
}
