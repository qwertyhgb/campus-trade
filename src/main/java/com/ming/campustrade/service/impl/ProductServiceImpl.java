package com.ming.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.ProductStatus;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.ProductPublishDTO;
import com.ming.campustrade.dto.ProductQueryDTO;
import com.ming.campustrade.dto.ProductUpdateDTO;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.service.ProductService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.ProductVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品服务实现类
 *
 * <p><b>继承关系：</b></p>
 * <pre>{@code
 * ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService
 * }</pre>
 * <p>解释每一层：</p>
 * <ul>
 *   <li><b>ServiceImpl&lt;ProductMapper, Product&gt;</b> —— MyBatis-Plus 提供的通用 Service 实现基类。
 *       泛型参数1是 Mapper 类型，泛型参数2是实体类型。
 *       继承后自动拥有 save()、getById()、updateById()、removeById()、page() 等全套 CRUD 方法，
 *       方法内部直接调用 ProductMapper，不需要自己写 SQL。</li>
 *   <li><b>ProductService</b> —— 我们自己定义的 Service 接口，声明了商品模块的业务方法。
 *       实现 implements 后，必须覆写接口中定义的所有方法。</li>
 * </ul>
 *
 * <p><b>为什么注入 UserMapper？</b><br>
 * 商品 VO 需要携带卖家信息（昵称、头像），这些数据存在 user 表中。
 * 在商品列表查询时，需要根据 sellerId 批量查询卖家信息（selectByIds），
 * 直接注入 UserMapper 最直接高效，避免通过 UserService 中转。</p>
 *
 * <p><b>this.xxx() 的来源：</b><br>
 * 代码中大量使用 this.save()、this.getById()、this.updateById() 等，
 * 这些方法不是这个类自己写的，而是从父类 ServiceImpl 继承来的。
 * this.save(product) 等价于 productMapper.insert(product)，
 * this.getById(id) 等价于 productMapper.selectById(id)，
 * 只是 ServiceImpl 封装了一层，用起来更方便。</p>
 */
@Slf4j
@Service
@SuppressWarnings("null")
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    /**
     * 用户 Mapper，用于查询卖家信息（昵称、头像）
     *
     * <p>用 final 修饰，构造器注入，保证不可变（线程安全）。
     * 这是 Spring 推荐的依赖注入方式，比 @Autowired 字段注入更清晰、更利于测试。</p>
     */
    private final UserMapper userMapper;

    /**
     * 构造器注入
     *
     * <p>Spring 启动时发现 ProductServiceImpl 需要一个 UserMapper 类型的 Bean，
     * 会自动去容器里找到 UserMapper（因为 @Mapper 注解已注册它）并传入。
     * 这就是"构造器注入"——比 @Autowired 字段注入更推荐的方式。</p>
     *
     * @param userMapper 用户数据访问层
     */
    public ProductServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    // ==================== 发布商品 ====================

    /**
     * 发布商品
     *
     * <p><b>流程：</b>从 ThreadLocal 获取当前登录用户 ID → 构建 Product 实体 → 保存到数据库</p>
     *
     * <p><b>为什么用 UserHolder.getUserVO().getId() 而不是从参数传 userId？</b><br>
     * 因为 userId 来自登录拦截器验证通过的 token，是可信的。
     * 如果从请求参数传 userId，用户可以伪造（改一下请求体里的 userId 就能冒充别人发商品）。
     * 从 ThreadLocal 取则保证了 userId 一定是当前登录用户自己。</p>
     *
     * @param dto 发布请求参数（标题、价格、描述、成色等），已通过 @Valid 校验非空和格式
     */
    @Override
    public void publishProduct(ProductPublishDTO dto) {
        // 1. 从 ThreadLocal 拿到当前登录用户的 ID，作为卖家 ID
        Long sellerId = UserHolder.getUserVO().getId();

        // 2. 创建商品实体，把 DTO 中的数据拷贝过来
        //    DTO 是前端传来的数据，Entity 是要存入数据库的数据，两者职责不同
        log.info("发布商品：title={}, price={}, sellerId={}", dto.getTitle(), dto.getPrice(), sellerId);
        Product product = new Product();
        product.setTitle(dto.getTitle());
        product.setDescription(dto.getDescription());
        product.setPrice(dto.getPrice());
        product.setOriginalPrice(dto.getOriginalPrice());
        product.setImage(dto.getImage());
        product.setCategoryId(dto.getCategoryId());
        product.setConditionLevel(dto.getConditionLevel());
        product.setSellerId(sellerId);     // 卖家 ID 来自登录态，不可伪造
        product.setStatus(ProductStatus.ON_SALE);              // 新发布的商品默认"在售"（1=在售）
        product.setViewCount(0);           // 初始浏览量为 0

        // 3. 保存到数据库
        //    this.save() 是父类 ServiceImpl 的方法，内部执行 INSERT INTO product (...) VALUES (...)
        this.save(product);
        log.info("发布商品成功：productId={}, title={}", product.getId(), product.getTitle());
    }

    // ==================== 修改商品 ====================

    /**
     * 修改商品（仅卖家本人可操作）
     *
     * <p><b>流程：</b>查商品是否存在 → 校验是否本人 → 部分更新 → 写回数据库</p>
     *
     * <p><b>什么是"部分更新"（Partial Update）？</b><br>
     * 前端可能只修改了标题和价格，其他字段不变。
     * ProductUpdateDTO 中所有字段都是可选的（没有 @NotNull），
     * 所以这里逐个判断：字段不为 null 才覆盖，为 null 就跳过保持原值。
     * 这样前端只需传需要改的字段。</p>
     *
     * <p><b>String 用 StringUtils.hasText() 而不是 != null？</b><br>
     * 因为空字符串 "" 和纯空格 "   " 在业务上等同于没有值。
     * hasText() 会排除 null、"" 和纯空白字符，比 != null 更严格、更安全。</p>
     *
     * @param id  商品 ID（URL 路径参数）
     * @param dto 修改请求参数（所有字段可选，只传需要改的）
     */
    @Override
    public void updateProduct(Long id, ProductUpdateDTO dto) {
        log.info("编辑商品：productId={}", id);
        // 1. 根据 ID 查询商品（MyBatis-Plus 自动加 WHERE deleted=0，已删除的查不到）
        Product product = this.getById(id);
        if (product == null) {
            // 商品不存在 → 抛出业务异常，全局异常处理器会捕获并返回错误响应
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 校验权限：只有商品的主人才能修改
        checkOwnership(product);

        // 3. 逐个字段判断：DTO 中非空的才覆盖到实体上（部分更新）
        if (StringUtils.hasText(dto.getTitle())) {
            product.setTitle(dto.getTitle());
        }
        if (StringUtils.hasText(dto.getDescription())) {
            product.setDescription(dto.getDescription());
        }
        if (dto.getPrice() != null) {
            product.setPrice(dto.getPrice());
        }
        if (dto.getOriginalPrice() != null) {
            product.setOriginalPrice(dto.getOriginalPrice());
        }
        if (StringUtils.hasText(dto.getImage())) {
            product.setImage(dto.getImage());
        }
        if (dto.getCategoryId() != null) {
            product.setCategoryId(dto.getCategoryId());
        }
        if (dto.getConditionLevel() != null) {
            product.setConditionLevel(dto.getConditionLevel());
        }

        // 4. 写回数据库
        //    this.updateById() 内部执行 UPDATE product SET ... WHERE id=? AND deleted=0
        //    只更新实体中被 set 过的字段，未 set 的字段不会出现在 SQL 中
        this.updateById(product);
        log.info("编辑商品成功：productId={}", id);
    }

    // ==================== 删除商品 ====================

    /**
     * 删除商品（仅卖家本人可操作，逻辑删除）
     *
     * <p><b>逻辑删除 vs 物理删除：</b></p>
     <ul>
     *   <li><b>物理删除</b>（DELETE FROM product WHERE id=?）：数据真的从数据库消失，不可恢复</li>
     *   <li><b>逻辑删除</b>（UPDATE product SET deleted=1 WHERE id=?）：只是标记 deleted=1，数据还在</li>
     * </ul>
     * <p>电商项目几乎都用逻辑删除——用户可能误删，需要保留恢复的可能性；
     * 同时已删除的数据在审计、数据分析时仍有价值。</p>
     *
     * <p><b>this.removeById(id) 为什么是逻辑删除？</b><br>
     * 因为 Product 实体的 deleted 字段标注了 @TableLogic，
     * MyBatis-Plus 会自动把 removeById 的 DELETE 改写成 UPDATE SET deleted=1。
     * 后续所有查询也会自动追加 WHERE deleted=0，过滤掉已删除的记录。</p>
     *
     * @param id 商品 ID
     */
    @Override
    public void deleteProduct(Long id) {
        log.info("删除商品：productId={}", id);
        // 1. 查商品是否存在
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 校验权限：只有本人能删自己的商品
        checkOwnership(product);

        // 3. 逻辑删除（@TableLogic 自动把 DELETE 转成 UPDATE SET deleted=1）
        this.removeById(id);
        log.info("删除商品成功：productId={}", id);
    }

    // ==================== 查看商品详情 ====================

    /**
     * 查看商品详情（浏览量 +1）
     *
     * <p><b>流程：</b>查商品 → 浏览量+1 → 查卖家信息 → 拼装 VO 返回</p>
     *
     * <p><b>浏览量为什么先查再改再存，而不是直接 SQL 加 1？</b><br>
     * 直接写 SQL（setSql("view_count = view_count + 1")）是原子操作，并发安全，
     * 但对新手不够直观。这里用"查出来 → Java 里加 1 → 存回去"的方式更容易理解。
     * 唯一的缺点是高并发下可能丢失少量浏览量（两人同时读到 10，各加 1 写回 11，实际应 12），
     * 校园平台完全不会有这个问题，等量级上去了再优化也不迟。</p>
     *
     * <p><b>为什么返回 ProductVO 而不是 Product？</b><br>
     * Product 实体里只有 sellerId（数字），没有卖家昵称和头像。
     * 前端展示商品详情时需要显示"谁在卖"，所以需要再查一次 User 表，
     * 把卖家的昵称和头像拼到 VO 里一起返回。</p>
     *
     * @param id 商品 ID
     * @return 包含卖家信息的商品视图对象
     */
    @Override
    public ProductVO getProductById(Long id) {
        log.info("查询商品详情：productId={}", id);
        // 1. 查商品
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 浏览量 +1（简单写法：查出当前值，在 Java 中加 1，写回数据库）
        product.setViewCount(product.getViewCount() + 1);
        this.updateById(product);

        // 3. 根据 sellerId 查卖家信息（昵称、头像）
        //    userMapper 是本类注入的 UserMapper，直接调用 selectById
        User seller = userMapper.selectById(product.getSellerId());

        // 4. 把 Product 实体 + User 实体拼成 ProductVO 返回给前端
        log.info("查询商品详情成功：productId={}, viewCount={}", id, product.getViewCount());
        return convertToProductVO(product, seller);
    }

    // ==================== 商品列表（分页 + 筛选 + 排序）====================

    /**
     * 商品列表查询（只查在售商品，支持关键词搜索、分类筛选、价格区间、成色筛选、排序）
     *
     * <p><b>这是最复杂的方法，拆成 5步理解：</b></p>
     * <ol>
     *   <li>构建查询条件（LambdaQueryWrapper）</li>
     *   <li>分页查询（Page + 分页拦截器自动加 LIMIT）</li>
     *   <li>批量查询卖家信息（避免 N+1 问题）</li>
     *   <li>转换为 VO 列表</li>
     *   <li>组装分页返回对象</li>
     * </ol>
     *
     * <p><b>什么是 N+1 查询问题？</b><br>
     * 假设一页 10 个商品来自 8 个卖家。如果对每个商品分别查一次卖家，
     * 就是 1（查商品）+ 10（逐个查卖家）= 11 次 SQL，这就是 N+1 问题。
     * 我们的做法：先收集所有 sellerId 去重，再用 selectByIds 一次查出全部卖家，
     * 总共只需 2 次 SQL，性能提升巨大。</p>
     *
     * <p><b>什么是 LambdaQueryWrapper？</b><br>
     * MyBatis-Plus 的条件构造器，用 Java 代码（而不是写 SQL 字符串）来拼 WHERE 条件。
     * 好处是类型安全（字段名写错编译期就报错）、防 SQL 注入、代码可读性高。
     * 例如 wrapper.eq(Product::getStatus, 1) 会生成 WHERE status = 1。</p>
     *
     * @param query 查询参数（页码、每页条数、关键词、分类、价格区间、成色、排序方式）
     * @return 分页结果，包含 records（商品 VO 列表）、total（总条数）、current（当前页）等
     */
    @Override
    public IPage<ProductVO> listProducts(ProductQueryDTO query) {
        log.info("商品列表查询：keyword={}, categoryId={}, pageNo={}, pageSize={}",
                query.getKeyword(), query.getCategoryId(), query.getPageNo(), query.getPageSize());
        // ===== 第 1 步：构建查询条件 =====

        // LambdaQueryWrapper：用 Lambda 表达式引用字段名（Product::getStatus），
        // 比字符串写法（"status"）更安全——字段名改了编译就会报错，不会运行时才发现
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        // 固定条件：只查在售商品（status=1），下架(0)和已售(2)的不在列表中显示
        // 等价 SQL: WHERE status = 1
        wrapper.eq(Product::getStatus, ProductStatus.ON_SALE);

        // 可选条件：关键词模糊搜索（标题包含关键词）
        // hasText 排除了 null、空字符串、纯空白，只有用户真的传了关键词才加这个条件
        // 等价 SQL: AND title LIKE '%关键词%'
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(Product::getTitle, query.getKeyword());
        }

        // 可选条件：分类筛选
        // 等价 SQL: AND category_id = ?
        if (query.getCategoryId() != null) {
            wrapper.eq(Product::getCategoryId, query.getCategoryId());
        }

        // 可选条件：最低价筛选（ge = greater than or equal，大于等于）
        // 等价 SQL: AND price >= ?
        if (query.getMinPrice() != null) {
            wrapper.ge(Product::getPrice, query.getMinPrice());
        }

        // 可选条件：最高价筛选（le = less than or equal，小于等于）
        // 等价 SQL: AND price <= ?
        if (query.getMaxPrice() != null) {
            wrapper.le(Product::getPrice, query.getMaxPrice());
        }

        // 可选条件：成色筛选（0全新 1几乎全新 2轻微使用痕迹 3明显使用痕迹）
        // 等价 SQL: AND condition_level = ?
        if (query.getConditionLevel() != null) {
            wrapper.eq(Product::getConditionLevel, query.getConditionLevel());
        }

        // 排序：根据前端传的 sort 参数决定排序方式
        // 等价 SQL: ORDER BY price ASC / ORDER BY price DESC / ORDER BY create_time DESC
        if ("price_asc".equals(query.getSort())) {
            wrapper.orderByAsc(Product::getPrice);        // 价格从低到高
        } else if ("price_desc".equals(query.getSort())) {
            wrapper.orderByDesc(Product::getPrice);       // 价格从高到低
        } else {
            wrapper.orderByDesc(Product::getCreateTime);  // 默认：按发布时间倒序（最新的在前）
        }

        // ===== 第 2 步：分页查询 =====

        // Page 对象：第 1 个参数是页码，第 2 个参数是每页条数
        // new Page<>(1, 10) 表示查第 1 页，每页 10 条
        Page<Product> page = new Page<>(query.getPageNo(), query.getPageSize());

        // this.page() 是父类 ServiceImpl 的方法，内部执行：
        //   1. SELECT COUNT(*) FROM product WHERE ... （查总数，用于计算总页数）
        //   2. SELECT * FROM product WHERE ... LIMIT 0, 10 （分页查数据，LIMIT 由分页拦截器自动追加）
        // 返回的 productPage 中包含：records（数据列表）、total（总条数）、pages（总页数）等
        Page<Product> productPage = this.page(page, wrapper);

        // ===== 第 3 步：批量查询卖家信息（避免 N+1 问题）=====

        // 从查询结果中提取所有 sellerId，去重（同一个卖家可能有多件商品在同一页）
        // stream().map() 把 Product 对象映射成 sellerId
        // .distinct() 去重
        // .toList() 收集成 List
        List<Long> sellerIds = productPage.getRecords().stream()
                .map(Product::getSellerId)
                .distinct()
                .toList();

        // 批量查询：一次查出所有卖家的信息
        // selectByIds 内部执行 SELECT * FROM user WHERE id IN (?, ?, ...)
        // 然后转成 Map<用户ID, User对象>，方便后续 O(1) 查找
        //
        // 三元运算符：如果 sellerIds 为空（没有商品），返回空 Map；否则批量查询
        // Map.of() 返回不可变空 Map，比 new HashMap<>() 更省内存
        //
        // Collectors.toMap 第一个参数是 key 提取器（User::getId → 用用户 ID 作 key），
        //                   第二个参数是 value 提取器（u -> u → User 对象本身作 value）
        Map<Long, User> sellerMap = sellerIds.isEmpty()
                ? Map.of()
                : userMapper.selectByIds(sellerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        // ===== 第 4 步：转换为 VO 列表 =====

        // 遍历每个 Product，从 sellerMap 中找到对应的卖家信息，拼成 ProductVO
        // sellerMap.get(product.getSellerId()) 是 O(1) 查找，比逐个查数据库快得多
        List<ProductVO> voList = productPage.getRecords().stream()
                .map(product -> convertToProductVO(product, sellerMap.get(product.getSellerId())))
                .toList();

        // ===== 第 5 步：组装分页返回对象 =====

        // 创建一个新的 Page<ProductVO>，把分页元信息从 productPage 搬过来
        // 不能直接返回 productPage，因为它的泛型是 Product，而我们需要返回 ProductVO
        Page<ProductVO> voPage = new Page<>();
        voPage.setRecords(voList);                        // 当前页的数据列表（VO 类型）
        voPage.setTotal(productPage.getTotal());           // 总记录数
        voPage.setCurrent(productPage.getCurrent());       // 当前页码
        voPage.setSize(productPage.getSize());             // 每页条数
        // setPages 已弃用，MyBatis-Plus 会在 setTotal 时自动计算总页数 = ceil(total / size)

        return voPage;
    }

    // ==================== 修改商品状态（上架/下架/标记已售）====================

    /**
     * 修改商品状态
     *
     * <p>状态值含义：0=下架（不在列表中显示）、1=在售（正常显示）、2=已售（交易完成）</p>
     *
     * <p>使用场景：</p>
     * <ul>
     *   <li>卖家主动下架 → status=0</li>
     *   <li>卖家重新上架 → status=1</li>
     *   <li>交易完成后标记已售 → status=2</li>
     * </ul>
     *
     * @param id     商品 ID
     * @param status 新状态值（0/1/2）
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        log.info("修改商品状态：productId={}, status={}", id, status);
        // 1. 查商品是否存在
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 校验权限：只有本人能改自己商品的状态
        checkOwnership(product);

        // 3. 校验状态值合法性（防止前端传一个 99 之类的无效值）
        if (status == null || (status != ProductStatus.OFF_SALE && status != ProductStatus.ON_SALE)) {
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "只能设置为上架或下架");
        }

        // 4. 更新状态并写回数据库
        product.setStatus(status);
        this.updateById(product);
        log.info("修改商品状态成功：productId={}, status={}", id, status);
    }

    // ==================== 我的商品 ====================

    /**
     * 查询当前登录用户发布的所有商品（包括下架和已售的）
     *
     * <p><b>和 listProducts 的区别：</b></p>
     * <ul>
     *   <li>listProducts：面向买家，只显示在售商品（status=1），任何人都能看</li>
     *   <li>getMyProducts：面向卖家，显示自己所有状态的商品，需要登录</li>
     * </ul>
     *
     * <p><b>为什么卖家信息不用批量查询？</b><br>
     * 因为这里所有商品的卖家都是当前登录用户自己，查一次就够了，不需要批量。</p>
     *
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @Override
    public IPage<ProductVO> getMyProducts(Integer pageNo, Integer pageSize) {
        log.info("查询我的商品列表：sellerId={}, pageNo={}, pageSize={}",
                UserHolder.getUserVO().getId(), pageNo, pageSize);
        // 1. 从 ThreadLocal 拿当前登录用户 ID
        Long sellerId = UserHolder.getUserVO().getId();

        // 2. 构建查询条件：seller_id = 当前用户，按发布时间倒序
        //    注意这里不加 status=1 条件，下架和已售的也要显示
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getSellerId, sellerId)
                .orderByDesc(Product::getCreateTime);

        // 3. 分页查询
        Page<Product> page = new Page<>(pageNo, pageSize);
        Page<Product> productPage = this.page(page, wrapper);

        // 4. 查一次当前用户信息（所有商品都是自己的，查一次就够）
        User seller = userMapper.selectById(sellerId);

        // 5. 转换为 VO 列表
        List<ProductVO> voList = productPage.getRecords().stream()
                .map(product -> convertToProductVO(product, seller))
                .toList();

        // 6. 组装分页返回对象（和 listProducts 一样的套路）
        Page<ProductVO> voPage = new Page<>();
        voPage.setRecords(voList);
        voPage.setTotal(productPage.getTotal());
        voPage.setCurrent(productPage.getCurrent());
        voPage.setSize(productPage.getSize());

        return voPage;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 校验当前登录用户是否是商品的主人
     *
     * <p>在修改、删除、上下架操作前调用，防止用户操作别人的商品。</p>
     *
     * <p><b>为什么抽成方法？</b><br>
     * updateProduct、deleteProduct、updateStatus 三个方法都要做同样的权限校验。
     * 如果不抽取，同样的代码要写三遍——以后改逻辑还要改三处，容易漏。
     * 抽成方法后，调用一行 checkOwnership(product) 就行。</p>
     *
     * <p><b>为什么用 .equals() 而不是 ==？</b><br>
     * sellerId 和 currentUserId 都是 Long 类型（对象，不是基本类型 long）。
     * Java 中 == 比较对象比较的是引用地址，不是值。
     * 两个 new Long(1) 用 == 比较结果可能是 false，但用 .equals() 比较一定是 true。
     * 所以包装类型比较值一定要用 .equals()。</p>
     *
     * @param product 要操作的商品
     * @throws BusinessException 如果当前用户不是商品主人，抛出 403 FORBIDDEN
     */
    private void checkOwnership(Product product) {
        Long currentUserId = UserHolder.getUserVO().getId();
        if (!product.getSellerId().equals(currentUserId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作他人商品");
        }
    }

    /**
     * 实体转 VO（Product + User → ProductVO）
     *
     * <p><b>为什么需要转换？</b><br>
     * Product 实体对应数据库结构，有 password、deleted 等敏感/内部字段。
     * ProductVO 是给前端看的视图对象，只包含前端需要的字段，还额外拼了卖家信息。
     * 这种"实体 → VO"的转换在分层架构中很常见，目的是隔离数据层和展示层。</p>
     *
     * <p><b>为什么是 static？</b><br>
     * 这个方法不依赖实例状态（不用 this.xxx），只是纯粹的属性拷贝。
     * 标记 static 后可以通过类名直接调用，也不需要创建对象就能使用。</p>
     *
     * @param product 商品实体（从数据库查出的）
     * @param seller  卖家实体（可能为 null，比如卖家账号已删除）
     * @return 拼装好的商品视图对象
     */
    private static ProductVO convertToProductVO(Product product, User seller) {
        ProductVO vo = new ProductVO();
        // 拷贝商品自身字段
        vo.setId(product.getId());
        vo.setTitle(product.getTitle());
        vo.setDescription(product.getDescription());
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setImage(product.getImage());
        vo.setCategoryId(product.getCategoryId());
        vo.setConditionLevel(product.getConditionLevel());
        vo.setStatus(product.getStatus());
        vo.setViewCount(product.getViewCount());
        vo.setCreateTime(product.getCreateTime());

        // 拼接卖家信息（可能为 null，所以加了判空保护）
        if (seller != null) {
            vo.setSellerId(seller.getId());
            vo.setSellerNickname(seller.getNickname());
            vo.setSellerAvatar(seller.getAvatar());
        }

        return vo;
    }
}
