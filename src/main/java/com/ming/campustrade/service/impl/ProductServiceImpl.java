package com.ming.campustrade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.ProductStatus;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.ProductPublishDTO;
import com.ming.campustrade.dto.ProductQueryDTO;
import com.ming.campustrade.dto.ProductUpdateDTO;
import com.ming.campustrade.entity.Comment;
import com.ming.campustrade.entity.Favorite;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.CommentMapper;
import com.ming.campustrade.mapper.FavoriteMapper;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.service.ProductService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.ProductVO;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
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
 * <p><b>为什么注入 StringRedisTemplate 和 ObjectMapper？</b><br>
 * 商品详情是高频读取接口（每次打开商品页都会调用），如果每次都查 MySQL，
 * 数据库压力会很大。我们使用 Redis 缓存商品详情（Cache-Aside 模式）：
 * 第一次查 MySQL 并把结果序列化为 JSON 存入 Redis，后续直接从 Redis 读取。
 * StringRedisTemplate 负责 Redis 读写，ObjectMapper 负责 Java 对象 ↔ JSON 的序列化/反序列化。</p>
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
@SuppressWarnings("null") // 抑制 MyBatis-Plus Lambda 方法引用与空类型分析冲突的误报警告
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    /**
     * 用户 Mapper，用于查询卖家信息（昵称、头像）
     *
     * <p>用 final 修饰，构造器注入，保证不可变（线程安全）。
     * 这是 Spring 推荐的依赖注入方式，比 @Autowired 字段注入更清晰、更利于测试。</p>
     */
    private final UserMapper userMapper;

    /**
     * Redis 操作模板，用于商品详情缓存的读写
     *
     * <p>StringRedisTemplate 是 Spring Data Redis 提供的操作类，
     * 专门处理 Key 和 Value 都是 String 类型的场景。
     * 我们把 ProductVO 序列化为 JSON 字符串存入 Redis，所以用它就够了。</p>
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * JSON 序列化/反序列化工具（Jackson 提供）
     *
     * <p>用于把 ProductVO 对象转成 JSON 字符串存入 Redis，
     * 以及从 Redis 取出 JSON 字符串后还原为 ProductVO 对象。</p>
     */
    private final ObjectMapper objectMapper;

    /**
     * 留言 Mapper，用于删除商品时清理关联的留言数据
     */
    private final CommentMapper commentMapper;

    /**
     * 收藏 Mapper，用于删除商品时清理关联的收藏数据
     */
    private final FavoriteMapper favoriteMapper;

    /**
     * 构造器注入
     *
     * <p>Spring 启动时发现 ProductServiceImpl 需要这些类型的 Bean，
     * 会自动去容器里找到对应实例并传入。
     * 这就是"构造器注入"——比 @Autowired 字段注入更推荐的方式。</p>
     *
     * @param userMapper          用户数据访问层
     * @param stringRedisTemplate Redis 操作模板
     * @param objectMapper        JSON 序列化工具
     * @param commentMapper       留言数据访问层
     * @param favoriteMapper      收藏数据访问层
     */
    public ProductServiceImpl(UserMapper userMapper, StringRedisTemplate stringRedisTemplate,
                              ObjectMapper objectMapper, CommentMapper commentMapper, FavoriteMapper favoriteMapper) {
        this.userMapper = userMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.commentMapper = commentMapper;
        this.favoriteMapper = favoriteMapper;
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
     * <p><b>为什么发布后是"待审核"而不是直接"在售"？</b><br>
     * 校园平台需要对商品内容进行合规审核（防止违规物品、虚假信息）。
     * 用户发布后商品进入 PENDING_REVIEW（待审核）状态，此时不会出现在商品列表中，
     * 需管理员调用审核接口通过后才能上架（变为 ON_SALE）。</p>
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
        product.setStatus(ProductStatus.PENDING_REVIEW);       // 新发布的商品默认"待审核"（4），需管理员审核后才上架
        product.setViewCount(0);           // 初始浏览量为 0

        // 3. 保存到数据库
        //    this.save() 是父类 ServiceImpl 的方法，内部执行 INSERT INTO product (...) VALUES (...)
        this.save(product);
        log.info("发布商品成功（待审核）：productId={}, title={}", product.getId(), product.getTitle());
    }

    // ==================== 修改商品 ====================

    /**
     * 修改商品（仅卖家本人可操作）
     *
     * <p><b>流程：</b>查商品是否存在 → 校验是否本人 → 部分更新 → 写回数据库 → 清除缓存</p>
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

        // 3. 状态限制：锁定/已售的商品禁止编辑
        //    LOCKED（有进行中的订单）：此时改价格/标题会让买卖双方对交易内容产生分歧
        //    SOLD（已成交）：交易已完成，商品信息应作为历史凭证保留，不允许再改
        Integer currentStatus = product.getStatus();
        if (currentStatus != null
                && (currentStatus == ProductStatus.LOCKED || currentStatus == ProductStatus.SOLD)) {
            log.warn("商品当前状态不允许编辑：productId={}, status={}", id, currentStatus);
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "当前商品状态不允许编辑");
        }

        // 记录修改前的状态，用于判断编辑后是否需要重新审核
        boolean wasOnSale = currentStatus != null && currentStatus == ProductStatus.ON_SALE;

        // 4. 构建带状态条件的更新链（防“先检查后操作”竞态）
        //    WHERE 带上“当前状态”条件：如果在查询后、更新前商品被并发锁定/售出（状态变了），
        //    这条 UPDATE 影响行数=0，我们会报错拒绝，避免用旧对象覆盖掉新状态。
        //    例如：卖家读到 ON_SALE → 买家下单锁定(LOCKED) → 卖家继续更新，
        //    因 WHERE status=ON_SALE 不匹配 LOCKED，影响行数=0，不会把 LOCKED 错误改成待审核。
        var updateChain = this.lambdaUpdate()
                .eq(Product::getId, id)
                .eq(Product::getStatus, currentStatus);

        // 5. 逐个字段判断：DTO 中非空的才写入（部分更新）
        if (StringUtils.hasText(dto.getTitle())) {
            updateChain.set(Product::getTitle, dto.getTitle());
        }
        if (StringUtils.hasText(dto.getDescription())) {
            updateChain.set(Product::getDescription, dto.getDescription());
        }
        if (dto.getPrice() != null) {
            updateChain.set(Product::getPrice, dto.getPrice());
        }
        if (dto.getOriginalPrice() != null) {
            updateChain.set(Product::getOriginalPrice, dto.getOriginalPrice());
        }
        if (StringUtils.hasText(dto.getImage())) {
            updateChain.set(Product::getImage, dto.getImage());
        }
        if (dto.getCategoryId() != null) {
            updateChain.set(Product::getCategoryId, dto.getCategoryId());
        }
        if (dto.getConditionLevel() != null) {
            updateChain.set(Product::getConditionLevel, dto.getConditionLevel());
        }

        // 6. 已上架的商品被编辑后，必须重新审核（防止绕过审核修改成违规内容）
        //    商品内容变了，原来审核通过的结论不再可信，需打回待审核状态由管理员复审。
        //    同时清空旧的审核备注（避免卖家看到上一次驳回的过时原因）。
        if (wasOnSale) {
            updateChain.set(Product::getStatus, ProductStatus.PENDING_REVIEW);
            updateChain.set(Product::getReviewRemark, null);
            log.info("商品已上架后被编辑，转为待审核状态：productId={}", id);
        }

        // 7. 执行条件更新并检查影响行数
        boolean updated = updateChain.update();
        if (!updated) {
            // 影响行数=0：商品状态在查询后被并发修改（如被下单锁定），拒绝本次编辑
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "商品状态已变化，请刷新后重试");
        }

        // 8. 清除 Redis 缓存（Cache-Aside 模式的"写后失效"策略）
        //    商品数据变了，缓存里的旧数据必须删掉，否则用户会看到修改前的信息。
        //    下次查询时会重新从 MySQL 加载最新数据并写入缓存。
        evictProductCache(id);

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
    @Transactional // 删除商品 + 清理留言/收藏是多表写操作，需事务保证要么都成功要么都回滚
    public void deleteProduct(Long id) {
        log.info("删除商品：productId={}", id);
        // 1. 查商品是否存在
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
    
        // 2. 校验权限：只有本人能删自己的商品
        checkOwnership(product);
    
        // 3. 状态限制：锁定中的商品禁止删除
        //    LOCKED 表示有进行中的订单，此时删除商品会破坏订单关联的交易数据；
        //    应等订单完成（SOLD）或取消（释放回 ON_SALE）后再处理。
        Integer currentStatus = product.getStatus();
        if (currentStatus != null && currentStatus == ProductStatus.LOCKED) {
            log.warn("商品锁定中（有进行中订单），禁止删除：productId={}", id);
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "商品有进行中的订单，不能删除");
        }
    
        // 4. 条件逻辑删除（@TableLogic 自动把 DELETE 转成 UPDATE SET deleted=1）
        //    WHERE 带上“当前状态”条件防竞态：如果在查询后、删除前商品被并发下单锁定（状态变为 LOCKED），
        //    这条 UPDATE 因 status 不匹配影响行数=0，我们会报错拒绝，避免删掉有进行中订单的商品。
        LambdaQueryWrapper<Product> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(Product::getId, id)
                .eq(Product::getStatus, currentStatus);
        boolean removed = this.remove(deleteWrapper);
        if (!removed) {
            // 影响行数=0：商品状态在查询后被并发修改（如被下单锁定），拒绝删除
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "商品状态已变化，请刷新后重试");
        }
    
        // 5. 清理关联数据，避免产生“孤儿数据”
        //    商品删除后，它下面的留言和收藏若不清理会变成无主数据，
        //    既浪费存储，又可能在“我的收藏”等列表里查出已删除的商品。
        //    - 留言（Comment 有 @TableLogic）：delete 会被改写成逻辑删除 UPDATE SET deleted=1
        //    - 收藏（Favorite 无 @TableLogic）：delete 是真正的物理删除
        LambdaQueryWrapper<Comment> commentWrapper = new LambdaQueryWrapper<>();
        commentWrapper.eq(Comment::getProductId, id);
        int deletedComments = commentMapper.delete(commentWrapper);
    
        LambdaQueryWrapper<Favorite> favoriteWrapper = new LambdaQueryWrapper<>();
        favoriteWrapper.eq(Favorite::getProductId, id);
        int deletedFavorites = favoriteMapper.delete(favoriteWrapper);
    
        // 6. 清除缓存：商品已删除，缓存必须失效，否则用户还能看到已删除的商品
        evictProductCache(id);
    
        log.info("删除商品成功：productId={}, 同步清理留言{}条、收藏{}条", id, deletedComments, deletedFavorites);
    }

    // ==================== 查看商品详情（带 Redis 缓存）====================

    /**
     * 查看商品详情（浏览量 +1，带 Redis 缓存加速）
     *
     * <p><b>整体流程（Cache-Aside 旁路缓存模式）：</b></p>
     * <ol>
     *   <li>先查 Redis 缓存 → 命中则直接返回（不查 MySQL，速度快）</li>
     *   <li>缓存未命中 → 查 MySQL 数据库</li>
     *   <li>数据库也没有 → 缓存空值（防穿透），抛出异常</li>
     *   <li>数据库有 → 组装 VO → 写入 Redis 缓存 → 返回</li>
     * </ol>
     *
     * <p><b>什么是 Cache-Aside（旁路缓存）模式？</b><br>
     * 这是最经典的缓存使用模式，核心原则：
     * 读：先读缓存，命中就返回；未命中就读数据库，把结果写入缓存再返回。
     * 写：先更新数据库，再删除缓存（而不是更新缓存）。
     * 为什么写时删缓存而不是更新缓存？因为如果两个写请求并发，
     * 可能出现"后写的先更新缓存、先写的后更新缓存"导致缓存里是旧值。
     * 删缓存更简单安全——下次读的时候自然会加载最新数据。</p>
     *
     * <p><b>什么是缓存穿透？如何防御？</b><br>
     * 攻击者故意请求不存在的商品 ID（如 -1、999999999），
     * 缓存里永远没有，每次都会穿透到 MySQL，造成数据库压力。
     * 防御方法：查不到数据时，在 Redis 中存一个 "NULL" 标记（短过期时间），
     * 下次再请求同一个 ID 时，看到 "NULL" 就知道商品不存在，直接返回错误，不再查 MySQL。</p>
     *
     * <p><b>什么是缓存雪崩？如何防御？</b><br>
     * 如果大量缓存同时过期，所有请求瞬间涌向 MySQL，数据库可能被打垮。
     * 防御方法：给每个缓存的过期时间加一个随机偏移（0~10分钟），
     * 这样不同商品的缓存会在不同时间过期，请求被分散，不会形成洪峰。</p>
     *
     * <p><b>浏览量为什么用原子 SQL 而不是先查再改？</b><br>
     * 直接写 SQL（SET view_count = view_count + 1）是数据库层面的原子操作，
     * MySQL 行锁保证并发安全。如果用"查出来 → Java 里 +1 → 存回去"，
     * 两个线程同时读到 100，各 +1 写回 101，实际应该是 102——丢失了一次计数。
     * 校园平台并发不高，但养成原子操作的习惯很重要。</p>
     *
     * @param id 商品 ID
     * @return 包含卖家信息的商品视图对象
     */
    @Override
    public ProductVO getProductById(Long id) {
        log.debug("查询商品详情：productId={}", id);

        // ===== 第 1 步：尝试从 Redis 缓存读取 =====
        // 缓存 Key 格式：product:detail:{id}，例如 product:detail:101
        String cacheKey = RedisConstants.PRODUCT_DETAIL_KEY + id;

        // 为什么 Redis 读取也要 try-catch？
        // Redis 是外部服务，可能因为重启、网络抖动、内存满等原因连接失败。
        // 如果这里不加保护，Redis 一挂整个商品详情接口就返回 500——
        // 但 MySQL 里数据是完好的，用户本可以正常浏览。
        // 降级策略：Redis 异常时当作"缓存未命中"，直接走 MySQL 查询，保证接口可用。
        String cachedJson = null;
        try {
            cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("Redis 读取异常，降级为查询 MySQL：productId={}", id, e);
        }

        if (cachedJson != null) {
            // 缓存命中！不需要查 MySQL 了

            // 1a. 检查是否是空值标记（防缓存穿透）
            //     如果之前查过这个 ID 但商品不存在，Redis 里存的是 "NULL" 字符串
            if (RedisConstants.PRODUCT_NULL_VALUE.equals(cachedJson)) {
                log.debug("商品详情缓存命中（空值标记）：productId={}", id);
                throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
            }

            // 1b. 反序列化：把 JSON 字符串还原为 ProductVO 对象
            try {
                ProductVO vo = objectMapper.readValue(cachedJson, ProductVO.class);
                log.debug("商品详情缓存命中：productId={}", id);

                // 浏览量仍然要 +1（缓存命中不代表不需要计数）
                // 注意：缓存中的 viewCount 是写入缓存那一刻的值，不是实时的，
                // 这对校园平台完全可以接受，浏览量本身就不需要精确到个位
                incrementViewCount(id);

                return vo;
            } catch (Exception e) {
                // 1c. JSON 解析失败（可能是缓存数据损坏、格式变更等异常情况）
                //     删除这条坏缓存，走下面的 MySQL 查询逻辑重新加载
                log.warn("商品详情缓存解析失败，删除坏缓存：productId={}", id);
                stringRedisTemplate.delete(cacheKey);
            }
        }

        // ===== 第 2 步：缓存未命中，查询 MySQL 数据库 =====
        Product product = this.getById(id);

        // ===== 第 3 步：数据库也查不到 → 缓存空值 + 抛异常 =====
        if (product == null) {
            // 缓存空值（防穿透）：存入 "NULL" 标记，过期时间较短（5分钟）
            // 这样短时间内重复请求同一个不存在的 ID，不会再穿透到 MySQL
            cacheNullSafely(cacheKey, id);
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // ===== 第 3.5 步：公开详情只允许查看在售商品（安全关键）=====
        // 公开接口若不检查状态，攻击者可通过枚举 ID 看到待审核/已驳回/下架/锁定商品及其审核备注。
        // 这里统一报 PRODUCT_NOT_FOUND（而非“商品不可用”），避免暴露商品是否存在，防止枚举探测。
        // 卖家/管理员查看非在售商品请走专用接口（getMyProductById / 管理员详情）。
        if (product.getStatus() == null || product.getStatus() != ProductStatus.ON_SALE) {
            log.debug("公开详情拒绝非在售商品：productId={}, status={}", id, product.getStatus());
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // ===== 第 4 步：浏览量 +1（原子操作）=====
        incrementViewCount(id);

        // ===== 第 5 步：查询卖家信息，组装 VO =====
        User seller = userMapper.selectById(product.getSellerId());
        ProductVO vo = convertToProductVO(product, seller);

        // ===== 第 6 步：写入 Redis 缓存 =====
        // 过期时间 = 基础时间(30分钟) + 随机偏移(0~9分钟)
        // 随机偏移防止缓存雪崩：不同商品的缓存不会在同一时刻集体过期
        //
        // 为什么用 ThreadLocalRandom 而不是 new Random()？
        // new Random() 每次调用都创建新对象（浪费内存），且多线程下内部用 CAS 竞争种子（性能差）。
        // ThreadLocalRandom 是 Java 7+ 推荐的方式：每个线程有自己的随机数生成器，
        // 无竞争、无对象创建，并发性能远优于 Random。
        long ttl = RedisConstants.PRODUCT_DETAIL_TTL + ThreadLocalRandom.current().nextInt(10);

        try {
            // 序列化：把 ProductVO 对象转成 JSON 字符串存入 Redis
            String json = objectMapper.writeValueAsString(vo);
            // 注意：这里用 Duration.ofMinutes()，因为 TTL 常量的单位是分钟
            stringRedisTemplate.opsForValue().set(cacheKey, json, Duration.ofMinutes(ttl));
            log.info("商品详情写入缓存：productId={}, ttl={}分钟", id, ttl);
        } catch (Exception e) {
            // 缓存写入失败不影响正常业务（降级为每次查 MySQL），只记录警告日志
            // 不能因为 Redis 故障就让整个商品详情接口不可用
            log.warn("商品详情缓存写入失败（不影响正常返回）：productId={}", id, e);
        }

        return vo;
    }

    // ==================== 卖家/管理员商品详情 ====================

    /**
     * 卖家查看自己的商品详情（任意状态，含审核备注）
     *
     * <p><b>和公开详情 getProductById 的区别：</b></p>
     * <ul>
     *   <li>getProductById：公开接口，只能看别人的在售商品，不走这里</li>
     *   <li>getMyProductById：需登录 + 校验是本人，可看自己任意状态的商品
     *       （包括待审核/已驳回），并能看到审核备注（知道为何被驳回）</li>
     * </ul>
     *
     * <p>不走缓存：卖家查看频率低，且需要实时状态和审核备注，缓存反而会造成延迟。</p>
     *
     * @param id 商品 ID
     * @return 商品详情（含审核备注）
     */
    @Override
    public ProductVO getMyProductById(Long id) {
        log.info("卖家查看商品详情：productId={}", id);
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        // 校验权限：只有商品本人能看自己的（任意状态）详情
        checkOwnership(product);
        User seller = userMapper.selectById(product.getSellerId());
        return convertToProductVO(product, seller);
    }

    /**
     * 管理员查看商品详情（任意状态，含审核备注，不校验归属）
     *
     * <p>管理员需要查看平台任何商品（包括待审核/已驳回/下架）以进行审核和处理，
     * 所以不限制状态也不校验归属。权限由 Controller 层的 @RequireRole(1) 保证。</p>
     *
     * @param id 商品 ID
     * @return 商品详情
     */
    @Override
    public ProductVO getProductByIdForAdmin(Long id) {
        log.info("管理员查看商品详情：productId={}", id);
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
        User seller = userMapper.selectById(product.getSellerId());
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

    // ==================== 修改商品状态（上架/下架）====================

    /**
     * 修改商品状态（卖家主动下架 / 重新提交审核）
     *
     * <p><b>为什么不能卖家想设什么状态就设什么？（安全关键）</b><br>
     * 旧实现只校验目标值是 0 或 1，不看当前状态，导致卖家可以把
     * “待审核(4)”的商品直接改成“在售(1)”，一脚跨过管理员审核。
     * 现在改为“状态转换白名单”：只有明确允许的 from→to 组合才能执行。</p>
     *
     * <p><b>允许的状态转换（卖家侧）：</b></p>
     * <ul>
     *   <li>ON_SALE(1) → OFF_SALE(0)：卖家主动下架</li>
     *   <li>OFF_SALE(0) → PENDING_REVIEW(4)：下架后重新提交审核</li>
     *   <li>REJECTED(5) → PENDING_REVIEW(4)：被驳回后修改重新提交审核</li>
     * </ul>
     *
     * <p><b>为什么重新上架要走审核（不能 OFF_SALE → ON_SALE）？</b><br>
     * 如果允许直接重新上架，卖家可以“发布→审核通过→下架→改成违规内容→重新上架”绕过审核。
     * 所以任何重新上架都必须重新走审核流程。</p>
     *
     * <p><b>为什么用条件更新？</b><br>
     * UPDATE ... WHERE id=? AND status=当前状态。防止并发下两个请求同时改状态，
     * 只有第一个能成功（影响行数=1），第二个影响行数=0 会被拒绝。</p>
     *
     * @param id     商品 ID
     * @param status 目标状态（0=下架 / 4=重新提交审核）
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        log.info("修改商品状态：productId={}, targetStatus={}", id, status);
        // 1. 查商品是否存在
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }
    
        // 2. 校验权限：只有本人能改自己商品的状态
        checkOwnership(product);
    
        // 3. 校验状态转换是否合法（白名单）
        //    只有明确允许的 from→to 组合才放行，其余一律拒绝
        Integer current = product.getStatus();
        boolean legalTransition =
                (current != null && current == ProductStatus.ON_SALE && status != null && status == ProductStatus.OFF_SALE)
                || (current != null && current == ProductStatus.OFF_SALE && status != null && status == ProductStatus.PENDING_REVIEW)
                || (current != null && current == ProductStatus.REJECTED && status != null && status == ProductStatus.PENDING_REVIEW);
        if (!legalTransition) {
            log.warn("非法的商品状态变更：productId={}, current={}, target={}", id, current, status);
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "不允许的状态变更");
        }
    
        // 4. 条件更新：WHERE id=? AND status=当前状态，防并发篡改
        //    重新提交审核时顺便清空旧的驳回原因（避免残留上一次驳回的过时备注）
        boolean updated = this.lambdaUpdate()
                .eq(Product::getId, id)
                .eq(Product::getStatus, current)
                .set(Product::getStatus, status)
                .set(status == ProductStatus.PENDING_REVIEW, Product::getReviewRemark, null)
                .update();
        if (!updated) {
            // 影响行数=0：状态在查询后被并发修改了，拒绝本次操作
            throw new BusinessException(ResultCode.PRODUCT_STATUS_ERROR, "商品状态已变更，请刷新后重试");
        }
    
        // 5. 清除缓存：状态变了，缓存必须失效
        evictProductCache(id);
    
        log.info("修改商品状态成功：productId={}, {} → {}", id, current, status);
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

    // ==================== 管理员：审核商品 ====================

    /**
     * 管理员审核商品（通过上架 / 不通过下架）
     *
     * <p><b>流程：</b>查商品是否存在 → 校验是否为待审核状态 → 根据审核结果设置状态 → 写库 → 清缓存</p>
     *
     * <p><b>为什么要校验“是否为待审核状态”？</b><br>
     * 只有 PENDING_REVIEW 状态的商品才需要审核。如果商品已被审核过（在售/下架）、
     * 或已售出/锁定，都不应该再被审核操作。用条件更新（WHERE status = PENDING_REVIEW）
     * 还能防止两个管理员同时审核同一商品时的并发问题。</p>
     *
     * @param id       商品 ID
     * @param approved true=审核通过（上架），false=审核不通过（下架）
     * @param remark   审核备注（驳回原因，通过时可为空）
     */
    @Override
    public void reviewProduct(Long id, boolean approved, String remark) {
        log.info("管理员审核商品：productId={}, approved={}", id, approved);

        // 1. 查商品是否存在
        Product product = this.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 2. 校验状态：只有待审核的商品才能被审核
        if (product.getStatus() != ProductStatus.PENDING_REVIEW) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_PENDING_REVIEW);
        }

        // 3. 根据审核结果设置状态：通过 → 上架，不通过 → 已驳回
        //    驳回用专门的 REJECTED 状态（而非 OFF_SALE），让卖家能区分“被驳回”和“自己下架”
        int newStatus = approved ? ProductStatus.ON_SALE : ProductStatus.REJECTED;
        String reviewRemark = approved ? null : (StringUtils.hasText(remark) ? remark : "");

        // 4. 条件更新：WHERE id=? AND status=PENDING_REVIEW，防并发审核
        //    两个管理员同时审核同一商品时，只有第一个能成功（影响行数=1），
        //    后提交的因 status 已变影响行数=0，会被拒绝，不会覆盖前一个人的结果。
        boolean updated = this.lambdaUpdate()
                .eq(Product::getId, id)
                .eq(Product::getStatus, ProductStatus.PENDING_REVIEW)
                .set(Product::getStatus, newStatus)
                .set(Product::getReviewRemark, reviewRemark)
                .update();
        if (!updated) {
            // 影响行数=0：商品状态在查询后已被并发修改（如已被另一个管理员审核）
            throw new BusinessException(ResultCode.PRODUCT_NOT_PENDING_REVIEW);
        }

        // 5. 清除缓存（状态变了，缓存必须失效）
        evictProductCache(id);

        log.info("审核商品完成：productId={}, 结果={}", id, approved ? "通过上架" : "不通过驳回");
    }

    // ==================== 管理员：商品列表 ====================

    /**
     * 管理员查询商品列表（分页，可按状态筛选，含待审核商品）
     *
     * <p><b>和 listProducts 的区别：</b></p>
     * <ul>
     *   <li>listProducts：面向买家，只查在售商品（status=1）</li>
     *   <li>listProductsForAdmin：面向管理员，可查任意状态的商品（含待审核），支持按状态筛选</li>
     * </ul>
     *
     * @param status   商品状态筛选（null 表示查全部状态）
     * @param pageNo   页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @Override
    public IPage<ProductVO> listProductsForAdmin(Integer status, Integer pageNo, Integer pageSize) {
        log.info("管理员查询商品列表：status={}, pageNo={}, pageSize={}", status, pageNo, pageSize);

        // 1. 构建查询条件：可选状态筛选 + 按发布时间倒序
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(Product::getStatus, status);
        }
        wrapper.orderByDesc(Product::getCreateTime);

        // 2. 分页查询
        Page<Product> page = new Page<>(pageNo, pageSize);
        Page<Product> productPage = this.page(page, wrapper);

        // 3. 批量查询卖家信息（避免 N+1，与 listProducts 同样的优化思路）
        List<Long> sellerIds = productPage.getRecords().stream()
                .map(Product::getSellerId)
                .distinct()
                .toList();
        Map<Long, User> sellerMap = sellerIds.isEmpty()
                ? Map.of()
                : userMapper.selectByIds(sellerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        // 4. 转换为 VO 列表
        List<ProductVO> voList = productPage.getRecords().stream()
                .map(product -> convertToProductVO(product, sellerMap.get(product.getSellerId())))
                .toList();

        // 5. 组装分页返回对象
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
     * 清除指定商品的 Redis 缓存
     *
     * <p><b>什么时候调用？</b><br>
     * 在商品数据发生变化的操作之后调用：修改商品、删除商品、修改状态。
     * 这是 Cache-Aside 模式中"写后失效"的核心——数据变了就删缓存，
     * 下次读取时自然会从 MySQL 加载最新数据并重建缓存。</p>
     *
     * <p><b>为什么删除缓存而不是更新缓存？</b><br>
     * 1. 删除比更新简单，不需要重新组装 VO（可能还要查卖家信息）
     * 2. 避免并发写导致的缓存不一致问题
     * 3. 如果商品被删除了，更新缓存没有意义
     * 4. 懒加载思想：只有真正被访问时才重建缓存，节省资源</p>
     *
     * <p><b>删除失败怎么办？</b><br>
     * 用 try-catch 包裹，Redis 异常时只记警告日志，不向上抛出。
     * 原因：调用此方法时 MySQL 的写操作已经成功完成了，
     * 如果因为删缓存失败就抛异常，用户会收到 500 错误——
     * 但数据其实已经改好了，用户可能以为操作失败而重复提交。
     * 最坏情况：缓存多活一段时间（最多 30+9 分钟），过期后自然失效，
     * 下次读取时会从 MySQL 加载最新数据重建缓存。</p>
     *
     * @param productId 要清除缓存的商品 ID
     */
    private void evictProductCache(Long productId) {
        try {
            String cacheKey = RedisConstants.PRODUCT_DETAIL_KEY + productId;
            Boolean deleted = stringRedisTemplate.delete(cacheKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("已清除商品缓存：productId={}", productId);
            }
        } catch (Exception e) {
            // Redis 异常不影响业务主流程：MySQL 数据已经更新成功，
            // 缓存最迟在 TTL 到期后自然失效，不会造成永久数据不一致
            log.warn("清除商品缓存失败（不影响数据正确性，缓存将自然过期）：productId={}", productId, e);
        }
    }

    /**
     * 安全地写入空值缓存（防穿透），Redis 异常时降级不抛出
     *
     * <p><b>为什么要单独封装并加 try-catch？</b><br>
     * 商品不存在时本应返回“商品不存在”（PRODUCT_NOT_FOUND）。但如果此时 Redis
     * 正好故障，直接写空值会抛出连接异常，把本来的“商品不存在”变成 500 错误，
     * 误导用户。空值缓存只是优化（减少穿透），写入失败不应该影响正常的业务返回。
     * 所以这里降级处理：写不进去就算了，下次请求再查一次 MySQL 也无妨。</p>
     *
     * @param cacheKey 缓存 key
     * @param productId 商品 ID（仅用于日志）
     */
    private void cacheNullSafely(String cacheKey, Long productId) {
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    RedisConstants.PRODUCT_NULL_VALUE,
                    Duration.ofMinutes(RedisConstants.PRODUCT_NULL_TTL)
            );
            log.info("商品不存在，缓存空值防穿透：productId={}", productId);
        } catch (Exception e) {
            // 空值缓存写入失败不影响“商品不存在”的正常返回，只记警告日志
            log.warn("空值缓存写入失败（不影响正常返回）：productId={}", productId, e);
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
        vo.setReviewRemark(product.getReviewRemark());
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

    /**
     * 增加商品浏览量（原子操作，避免并发计数错误）
     *
     * <p><b>为什么要用这个写法，而不是先 SELECT 再 UPDATE？</b></p>
     * <pre>{@code
     * // ❌ 新手容易写成的方式（有并发问题）：
     * Product product = this.getById(id);               // 1. 先查出来
     * product.setViewCount(product.getViewCount() + 1); // 2. 内存中+1
     * this.updateById(product);                         // 3. 再写回去
     *
     * // 问题：两个线程同时读到 viewCount=100，各+1写回101，实际应该是102
     * // 这叫"读-改-写"竞态条件（Race Condition）
     *
     * // ✅ 本方法的写法（原子操作）：
     * // UPDATE product SET view_count = view_count + 1 WHERE id = ?
     * // MySQL 行锁保证同一时刻只有一个线程能更新这行，+1 不会被覆盖
     * }</pre>
     *
     * <p><b>方法调用链逐层解释：</b></p>
     * <ul>
     *   <li><b>this.lambdaUpdate()</b> —— MyBatis-Plus 的 Lambda 更新链式操作入口，
     *       返回 LambdaUpdateChainWrapper，允许用点号连接条件和方法</li>
     *   <li><b>.eq(Product::getId, id)</b> —— 生成 WHERE id = ?（eq = equal）</li>
     *   <li><b>.setSql("view_count = view_count + 1")</b> —— 在 SET 子句嵌入原始 SQL。
     *       不能用 .set(Product::getViewCount, xxx) 因为那只能赋固定值，
     *       无法表达"在原值基础上+1"。setSql 让数据库自己计算，保证原子性</li>
     *   <li><b>.update()</b> —— 执行 UPDATE，返回是否成功</li>
     * </ul>
     *
     * @param id 商品 ID（主键）
     */
    private void incrementViewCount(Long id) {
        this.lambdaUpdate()                 // 创建 Lambda 更新链
            .eq(Product::getId, id)         // WHERE id = ?
            .setSql("view_count = view_count + 1") // SET view_count = view_count + 1（原子自增）
            .update();                      // 执行 UPDATE
    }
}
