package com.ming.campustrade.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.PublicApi;
import com.ming.campustrade.dto.ProductPublishDTO;
import com.ming.campustrade.dto.ProductQueryDTO;
import com.ming.campustrade.dto.ProductUpdateDTO;
import com.ming.campustrade.service.ProductService;
import com.ming.campustrade.vo.ProductVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

/**
 * 商品管理控制器 —— 处理商品的发布、编辑、删除、查询、上下架等 HTTP 请求。
 *
 * <h2>核心注解说明</h2>
 * <ul>
 *   <li>{@code @RestController}：等价于 {@code @Controller + @ResponseBody}。
 *       标注后，该类中所有方法的返回值都会被自动序列化为 JSON 写入响应体，
 *       不需要在每个方法上单独加 {@code @ResponseBody}。
 *       这是 RESTful API 开发的标准写法。</li>
 *   <li>{@code @RequestMapping("/product")}：为该控制器下所有接口设置「基础路径前缀」。
 *       例如方法上映射 {@code @PostMapping("/publish")}，
 *       最终完整路径就是 {@code POST /product/publish}。</li>
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
 *   <li>{@code @PublicApi}：公开接口，无需登录即可访问（如浏览商品列表、查看详情）</li>
 *   <li>无注解：需要登录（由 LoginInterceptor 拦截），但不限制角色（如发布、编辑商品）</li>
 * </ul>
 *
 * @author Ming
 * @since 1.0.0
 */
@Slf4j
@Tag(name = "商品管理", description = "商品的发布、编辑、删除、查询、上下架等操作")
@RestController
@RequestMapping("/product")
public class ProductController {

    private final ProductService productService;

    /**
     * 构造器注入：Spring 启动时自动把 ProductService 的实现类实例传进来。
     * 因为只有一个构造器，所以不需要额外加 @Autowired 注解（Spring 4.3+ 特性）。
     */
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * 发布商品（需要登录）。
     *
     * <p>{@code @RequestBody} 从请求体读取 JSON 并反序列化为 ProductPublishDTO 对象。</p>
     * <p>{@code @Valid} 触发 Jakarta Validation 校验：DTO 上的 @NotBlank、@NotNull、@DecimalMin
     * 等注解会在此处自动生效，校验不通过直接返回 400 错误，不会进入方法体。</p>
     *
     * @param productPublishDTO 商品信息（标题、价格、分类、描述、图片等）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "发布商品", description = "卖家发布新商品，需要提供标题、价格、分类等信息")
    @PostMapping("/publish")
    public Result<Void> publish(@RequestBody @Valid ProductPublishDTO productPublishDTO) {
        log.info("发布商品：title={}, price={}", productPublishDTO.getTitle(), productPublishDTO.getPrice());
        productService.publishProduct(productPublishDTO);
        log.info("发布商品成功：title={}", productPublishDTO.getTitle());
        return Result.success();
    }

    /**
     * 编辑商品（需要登录，且只能编辑自己的商品）。
     *
     * <p>{@code @PutMapping("/{id}")} 使用 PUT 方法 + 路径变量，符合 RESTful 风格：
     * PUT 语义是「用新数据完整替换指定资源」。</p>
     * <p>{@code @PathVariable} 从 URL 路径中提取商品 ID：
     * 例如请求 PUT /product/42，则 id = 42。</p>
     *
     * @param id               商品ID（路径变量）
     * @param productUpdateDTO 要更新的商品信息
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "编辑商品", description = "卖家修改自己已发布的商品信息")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "商品ID") @PathVariable Long id,
                                @RequestBody @Valid ProductUpdateDTO productUpdateDTO) {
        log.info("编辑商品：productId={}, title={}", id, productUpdateDTO.getTitle());
        productService.updateProduct(id, productUpdateDTO);
        log.info("编辑商品成功：productId={}", id);
        return Result.success();
    }

    /**
     * 删除商品（需要登录，且只能删除自己的商品）。
     *
     * <p>这里是「逻辑删除」：数据库记录并不会真正被 DELETE，
     * 而是把 deleted 字段标记为 1，查询时自动过滤。
     * 这样做的好处是数据可恢复、可审计。</p>
     *
     * @param id 商品ID（路径变量）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "删除商品", description = "卖家删除自己的商品（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "商品ID") @PathVariable Long id) {
        log.info("删除商品：productId={}", id);
        productService.deleteProduct(id);
        log.info("删除商品成功：productId={}", id);
        return Result.success();
    }

    /**
     * 查询商品详情（公开接口，无需登录）。
     *
     * <p>{@code @PublicApi} 标记此接口跳过登录拦截器，
     * 因为未登录用户也应该能浏览商品详情（电商基本体验）。</p>
     *
     * @param id 商品ID（路径变量）
     * @return 商品详细信息（含卖家信息、分类名称等）
     */
    @Operation(summary = "查询商品详情", description = "根据商品ID获取商品详细信息（公开接口）")
    @PublicApi
    @GetMapping("/{id}")
    public Result<ProductVO> getById(@Parameter(description = "商品ID") @PathVariable Long id) {
        log.info("查询商品详情：productId={}", id);
        return Result.success(productService.getProductById(id));
    }

    /**
     * 商品列表查询（公开接口，无需登录）。
     *
     * <p>注意：这里的参数 ProductQueryDTO 没有加 {@code @RequestBody}，
     * 因为 GET 请求通常不带请求体。Spring 会自动把 URL 上的查询参数
     * （如 ?keyword=手机&categoryId=1&pageNo=1&pageSize=10）
     * 绑定到 DTO 的同名字段上，这叫「查询参数绑定」。</p>
     *
     * @param productQueryDTO 查询条件（关键词、分类ID、分页参数等）
     * @return 分页的商品列表（IPage 包含 records、total、pages 等分页信息）
     */
    @Operation(summary = "商品列表查询", description = "根据条件分页查询在售商品列表（公开接口），支持按分类、关键词等筛选")
    @PublicApi
    @GetMapping("/list")
    public Result<IPage<ProductVO>> list(ProductQueryDTO productQueryDTO) {
        log.info("商品列表查询：keyword={}, categoryId={}, pageNo={}, pageSize={}",
                productQueryDTO.getKeyword(), productQueryDTO.getCategoryId(),
                productQueryDTO.getPageNo(), productQueryDTO.getPageSize());
        return Result.success(productService.listProducts(productQueryDTO));
    }

    /**
     * 修改商品状态 —— 上架/下架（需要登录，且只能操作自己的商品）。
     *
     * <p>{@code @RequestParam} 从 URL 查询参数中取值：
     * 例如请求 POST /product/42/status?status=0，则 id=42, status=0（下架）。</p>
     * <p>为什么用 POST 而不是 PUT？因为这是一个「动作」而非「替换资源」，
     * 用 POST + 子路径 /status 语义更清晰。</p>
     *
     * @param id     商品ID（路径变量）
     * @param status 目标状态：0=下架，1=上架（查询参数）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "修改商品状态", description = "卖家上下架商品，status=0 下架，status=1 上架")
    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@Parameter(description = "商品ID") @PathVariable Long id,
                                      @Parameter(description = "状态：0下架 1上架") @RequestParam Integer status) {
        log.info("修改商品状态：productId={}, status={}", id, status);
        productService.updateStatus(id, status);
        log.info("修改商品状态成功：productId={}, status={}", id, status);
        return Result.success();
    }

    /**
     * 我的商品列表（需要登录）。
     *
     * <p>查看当前登录卖家发布的全部商品（含下架、在售、已售），分页返回。
     * 与 /product/list 的区别：list 只查在售商品且公开访问，
     * 而 /my 查的是「自己的所有商品」且需要登录。</p>
     *
     * <p>{@code @RequestParam(defaultValue = "1")} 表示如果前端没传这个参数，
     * 就使用默认值 1，避免空指针。</p>
     *
     * @param pageNo   页码，从1开始（查询参数，默认1）
     * @param pageSize 每页条数（查询参数，默认10）
     * @return 分页的商品列表
     */
    @Operation(summary = "我的商品列表", description = "查看当前登录卖家发布的全部商品（含下架、在售、已售），分页返回")
    @GetMapping("/my")
    public Result<IPage<ProductVO>> myProducts(@Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer pageNo,
                                                @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer pageSize) {
        log.info("查询我的商品列表：pageNo={}, pageSize={}", pageNo, pageSize);
        return Result.success(productService.getMyProducts(pageNo, pageSize));
    }
}
