package com.ming.campustrade.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.entity.Favorite;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.FavoriteMapper;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.service.FavoriteService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.FavoriteVO;

/**
 * 收藏模块 - 业务逻辑实现类
 *
 * 职责：处理用户收藏/取消收藏商品、判断是否已收藏、分页查询"我的收藏"列表等核心业务逻辑。
 *
 * 继承说明：
 * - ServiceImpl<FavoriteMapper, Favorite> 是 MyBatis-Plus 提供的通用 Service 基类，
 *   它已经帮我们实现了常用的 CRUD 方法（save、remove、getById、page、exists 等），
 *   我们只需要在里面编写自己特有的业务逻辑即可，不用重复造轮子。
 * - FavoriteService 是我们自己定义的业务接口，规定了收藏模块对外提供哪些能力。
 *
 * 设计要点：
 * - 收藏表采用「物理删除」而非逻辑删除。原因：收藏是轻量级关系数据，不需要审计痕迹；
 *   若使用逻辑删除（deleted 字段），数据库唯一索引 uk_user_product(user_id, product_id)
 *   会导致「取消收藏后再次收藏」时 INSERT 触发唯一约束冲突而静默失败。
 *   物理删除后记录真正从表中移除，再次收藏可以正常 INSERT，彻底规避此问题。
 */
@Slf4j
@Service // 告诉 Spring 容器：这是一个业务逻辑层（Service）组件，Spring 会自动扫描并创建它的实例（Bean）
public class FavoriteServiceImpl extends ServiceImpl<FavoriteMapper, Favorite> implements FavoriteService {

    // 声明商品和用户的数据访问接口（Mapper），用于跨模块的数据库操作。
    // 使用 final 修饰，确保在构造函数初始化后不能再被修改，符合安全和不变性原则。
    private final ProductMapper productMapper;
    private final UserMapper userMapper;

    /**
     * 构造函数注入（Spring 推荐的依赖注入方式）：
     * Spring 在实例化 FavoriteServiceImpl 时，会自动查找容器中的 ProductMapper 和 UserMapper
     * 实例并注入进来。相比 @Autowired 字段注入，构造函数注入的依赖不可变、更易测试。
     */
    public FavoriteServiceImpl(ProductMapper productMapper, UserMapper userMapper) {
        this.productMapper = productMapper;
        this.userMapper = userMapper;
    }

    /**
     * 添加收藏
     *
     * 流程说明：
     * 1. 获取当前登录用户的 ID
     * 2. 验证商品是否存在（不能收藏不存在的商品）
     * 3. 检查是否已经收藏过（幂等处理：重复收藏不报错，直接返回）
     * 4. 写入收藏记录；若并发场景下两个请求同时通过了第 3 步的检查，
     *    数据库唯一索引 uk_user_product 会拦住第二个 INSERT，
     *    通过捕获 DuplicateKeyException 来兜底，保证不会重复收藏
     *
     * @param productId 要收藏的商品 ID
     * @throws BusinessException 商品不存在时抛出
     */
    @Override
    public void addFavorite(Long productId) {
        // 1. 从线程局部变量 ThreadLocal 中获取当前登录用户的 ID（已由 LoginInterceptor 提前注入）。
        //    使用 Objects.requireNonNull 做空值保护：如果拦截器未正确注入用户信息，
        //    这里会立即抛出 NullPointerException 并附带明确的提示信息，方便排查问题。
        //    这比在类上贴 @SuppressWarnings("null") 压制警告更好——压制只是让编译器闭嘴，
        //    真正的空指针隐患依然存在。
        Long userId = Objects.requireNonNull(UserHolder.getUserVO(), "用户未登录，无法获取用户信息").getId();

        // 2. 根据商品 ID 查询商品是否存在
        Product product = productMapper.selectById(productId);
        if (product == null) {
            log.warn("收藏失败，商品不存在: userId={}, productId={}", userId, productId);
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 3. 检查是否已经收藏过（快速路径，避免不必要的 INSERT）
        //    使用 LambdaQueryWrapper 构建类型安全的查询条件，
        //    等价于 SQL：SELECT 1 FROM favorite WHERE user_id = ? AND product_id = ? LIMIT 1
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId)
               .eq(Favorite::getProductId, productId);

        //    exists() 是 MyBatis-Plus 3.5.x 提供的方法，底层执行 SELECT 1 ... LIMIT 1，
        //    只要找到一条匹配记录就返回 true，比 count() > 0（需要全量计数）更高效。
        if (this.exists(wrapper)) {
            log.info("用户已收藏过该商品，幂等返回: userId={}, productId={}", userId, productId);
            return; // 幂等处理：重复收藏不报错，直接返回成功
        }

        // 4. 构建收藏记录并落库
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setProductId(productId);
        // createTime 不需要手动设置——数据库建表时定义了 DEFAULT CURRENT_TIMESTAMP，
        // MyBatis-Plus 插入时如果该字段为 null，就不会出现在 INSERT 语句中，
        // 数据库会自动用当前时间填充。

        try {
            // save() 是 ServiceImpl 提供的方法，底层调用 favoriteMapper.insert(favorite)
            this.save(favorite);
            log.info("收藏成功: userId={}, productId={}", userId, productId);
        } catch (DuplicateKeyException exception) {
            // 并发兜底：两个请求几乎同时到达，都通过了第 3 步的 exists 检查，
            // 第一个 INSERT 成功，第二个 INSERT 触发唯一索引 uk_user_product 冲突。
            // 这里捕获异常并忽略，保证接口对调用方来说依然是"收藏成功"的语义。
            log.info("并发收藏冲突，已忽略重复收藏: userId={}, productId={}", userId, productId);
        }
    }

    /**
     * 取消收藏
     *
     * 流程说明：
     * 1. 获取当前登录用户的 ID
     * 2. 根据 userId + productId 定位收藏记录并物理删除
     * 3. 如果没有删除任何记录，说明用户本来就没收藏过该商品，抛出业务异常
     *
     * 为什么用物理删除？
     * 收藏是轻量级的用户-商品关系，不需要保留历史记录做审计。
     * 物理删除后记录真正从表中消失，用户再次收藏时可以正常 INSERT，
     * 不会和唯一索引 uk_user_product 冲突。
     *
     * @param productId 要取消收藏的商品 ID
     * @throws BusinessException 未收藏该商品时抛出
     */
    @Override
    public void removeFavorite(Long productId) {
        // 1. 获取当前登录用户的 ID
        Long userId = Objects.requireNonNull(UserHolder.getUserVO(), "用户未登录，无法获取用户信息").getId();

        // 2. 构建删除条件：user_id = 当前用户 AND product_id = 目标商品
        //    等价于 SQL：DELETE FROM favorite WHERE user_id = ? AND product_id = ?
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId)
               .eq(Favorite::getProductId, productId);

        //    remove() 是 ServiceImpl 提供的方法，底层调用 mapper.delete(wrapper)。
        //    因为 Favorite 实体没有 @TableLogic 注解，所以这里执行的是真正的 DELETE（物理删除），
        //    而不是 UPDATE deleted = 1（逻辑删除）。
        //    返回值：true 表示至少删除了一条记录，false 表示没有匹配的记录。
        boolean removed = this.remove(wrapper);

        // 3. 如果没有删除任何记录，说明用户本来就没有收藏过这个商品
        if (!removed) {
            log.warn("取消收藏失败，未收藏该商品: userId={}, productId={}", userId, productId);
            throw new BusinessException(ResultCode.FAVORITE_NOT_FOUND);
        }

        log.info("取消收藏成功: userId={}, productId={}", userId, productId);
    }

    /**
     * 判断当前用户是否已收藏指定商品
     *
     * 典型使用场景：前端进入商品详情页时调用此接口，
     * 根据返回值决定收藏按钮显示为"收藏"还是"已收藏"状态。
     *
     * @param productId 商品 ID
     * @return true = 已收藏，false = 未收藏
     */
    @Override
    public boolean isFavorited(Long productId) {
        // 1. 获取当前登录用户的 ID
        Long userId = Objects.requireNonNull(UserHolder.getUserVO(), "用户未登录，无法获取用户信息").getId();

        // 2. 构建查询条件：user_id = 当前用户 AND product_id = 目标商品
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId)
               .eq(Favorite::getProductId, productId);

        // 3. 使用 exists() 判断是否存在匹配记录。
        //    底层 SQL：SELECT 1 FROM favorite WHERE user_id = ? AND product_id = ? LIMIT 1
        //    相比 count(wrapper) > 0（SELECT COUNT(*) ...），exists 找到第一条就返回，性能更优。
        return this.exists(wrapper);
    }

    /**
     * 分页查询"我的收藏"列表
     *
     * 流程说明：
     * 1. 获取当前用户 ID，按收藏时间倒序分页查询收藏记录
     * 2. 从收藏记录中收集商品 ID，批量查询商品信息（避免 N+1 问题）
     * 3. 从商品信息中收集卖家 ID，批量查询卖家信息（同样避免 N+1）
     * 4. 将收藏记录 + 商品信息 + 卖家信息组装成 FavoriteVO 返回
     *
     * 什么是 N+1 问题？
     * 如果当前页有 10 条收藏， naive 的做法是在 for 循环里对每条收藏执行一次
     * selectById 查商品，再执行一次 selectById 查卖家——总共 21 次 SQL（1 次分页 + 20 次关联）。
     * 优化后只需要 3 次 SQL（1 次分页 + 1 次批量查商品 + 1 次批量查卖家），性能差距巨大。
     *
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 包含 FavoriteVO 数据的分页对象
     */
    @Override
    public IPage<FavoriteVO> getMyFavorites(Integer pageNo, Integer pageSize) {
        // 1. 获取当前登录用户的 ID
        Long userId = Objects.requireNonNull(UserHolder.getUserVO(), "用户未登录，无法获取用户信息").getId();

        // 2. 构建查询条件：只查当前用户的收藏，按收藏时间倒序排列（最新收藏的排最前面）
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId)
               .orderByDesc(Favorite::getCreateTime);

        // 3. 执行分页查询
        //    Page 对象是 MyBatis-Plus 的分页参数，传入 (页码, 每页条数)，
        //    分页拦截器（PaginationInnerInterceptor）会自动在 SQL 后面拼接 LIMIT 子句。
        Page<Favorite> page = new Page<>(pageNo, pageSize);
        Page<Favorite> favoritePage = this.page(page, wrapper);

        // 4. 如果当前页没有数据，直接返回一个空的分页对象，避免后续无意义的批量查询
        if (favoritePage.getRecords().isEmpty()) {
            Page<FavoriteVO> emptyPage = new Page<>();
            emptyPage.setTotal(0);
            emptyPage.setCurrent(favoritePage.getCurrent());
            emptyPage.setSize(favoritePage.getSize());
            return emptyPage;
        }

        // ========== 第一次批量查询：收藏记录 → 商品信息 ==========

        // 5. 从当前页的所有收藏记录中，提取不重复的商品 ID 列表
        //    .distinct() 去重：同一页内理论上不会重复，但加上更安全
        //    .toList() 是 Java 16+ 的 Stream 终结操作，返回不可变 List（比 collect(Collectors.toList()) 更简洁）
        List<Long> productIds = favoritePage.getRecords().stream()
                .map(Favorite::getProductId)
                .distinct()
                .toList();

        // 6. 根据商品 ID 列表，一次性批量查询所有商品（selectByIds 是 MyBatis-Plus 3.5.7+ 的新 API，
        //    替代了已弃用的 selectBatchIds）。
        //    查询结果转换为 Map<商品ID, 商品对象>，后续可以通过 ID 直接 O(1) 取到对应商品，
        //    不需要再遍历列表查找。
        Map<Long, Product> productMap = productMapper.selectByIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        // ========== 第二次批量查询：商品信息 → 卖家信息 ==========

        // 7. 从查到的商品中，提取不重复的卖家 ID 列表
        List<Long> sellerIds = productMap.values().stream()
                .map(Product::getSellerId)
                .distinct()
                .toList();

        // 8. 批量查询卖家信息，同样转为 Map<用户ID, 用户对象> 方便后续快速查找
        Map<Long, User> sellerMap = sellerIds.isEmpty()
                ? Map.of()  // 如果没有卖家 ID（理论上不会走到这里），返回空 Map 避免无意义的 SQL
                : userMapper.selectByIds(sellerIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        // ========== 数据组装：Entity → VO ==========

        // 9. 将收藏记录逐条转换为前端需要的 FavoriteVO 视图对象
        List<FavoriteVO> voList = favoritePage.getRecords().stream()
                // 过滤掉商品已被删除的收藏记录（商品被删除后 selectByIds 查不到，
                // productMap 中就不存在对应的 key）。
                // 注意：这会导致返回的 records 数量可能少于 pageSize，
                // 但 total 仍是收藏表的真实总数。如果需要精确分页，
                // 应在商品删除时同步清理关联的收藏记录（后续可优化）。
                .filter(fav -> productMap.containsKey(fav.getProductId()))
                .map(fav -> {
                    Product product = productMap.get(fav.getProductId());
                    User seller = sellerMap.get(product.getSellerId());
                    return convertToFavoriteVO(fav, product, seller);
                })
                .toList();

        // 10. 将转换好的 VO 列表装填进一个新的分页对象，并拷贝分页元数据后返回
        Page<FavoriteVO> voPage = new Page<>();
        voPage.setRecords(voList);
        voPage.setTotal(favoritePage.getTotal());   // 总记录数（收藏表的真实总数）
        voPage.setCurrent(favoritePage.getCurrent()); // 当前页码
        voPage.setSize(favoritePage.getSize());       // 每页条数

        return voPage;
    }

    /**
     * 内部辅助方法：将数据库实体（Entity）对象转换为前端视图（VO）对象
     *
     * 为什么不直接把 Favorite 实体返回给前端？
     * 1. Favorite 实体只有 userId、productId 等 ID 字段，前端需要的是商品标题、价格、卖家昵称等展示信息
     * 2. VO 可以隐藏数据库内部字段（如 deleted），只暴露前端需要的数据
     * 3. VO 的结构可以灵活调整，不影响数据库表结构
     *
     * @param favorite 收藏记录实体
     * @param product  关联的商品实体
     * @param seller   关联的卖家用户实体（可能为 null，比如卖家账号被注销）
     * @return 组装完成的 FavoriteVO
     */
    private static FavoriteVO convertToFavoriteVO(Favorite favorite, Product product, User seller) {
        FavoriteVO vo = new FavoriteVO();

        // 复制收藏记录本身的属性
        vo.setId(favorite.getId());
        vo.setCreateTime(favorite.getCreateTime());

        // 拼装商品关联信息
        vo.setProductId(product.getId());
        vo.setProductTitle(product.getTitle());
        vo.setProductPrice(product.getPrice());
        vo.setProductImage(product.getImage());
        vo.setProductStatus(product.getStatus()); // 前端可据此显示"已下架""已售出"等标签

        // 拼装卖家关联信息（卖家可能为 null，需要做空值保护）
        if (seller != null) {
            vo.setSellerId(seller.getId());
            vo.setSellerNickname(seller.getNickname());
        }

        return vo;
    }
}
