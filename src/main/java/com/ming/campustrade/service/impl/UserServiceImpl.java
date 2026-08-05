package com.ming.campustrade.service.impl;

import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.vo.LoginVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.common.constant.ProductStatus;
import com.ming.campustrade.dto.UserAddDTO;
import com.ming.campustrade.dto.UserLoginDTO;
import com.ming.campustrade.dto.UserPasswordUpdateDTO;
import com.ming.campustrade.dto.UserProfileUpdateDTO;
import com.ming.campustrade.dto.UserRegisterDTO;
import com.ming.campustrade.entity.Product;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.mapper.UserRoleMapper;
import com.ming.campustrade.service.ProductCacheService;
import com.ming.campustrade.service.UserService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

/**
 * 用户服务实现类
 *
 * <p><b>继承关系：</b></p>
 * <pre>{@code
 * UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService
 * }</pre>
 * <p>解释每一层：</p>
 * <ul>
 *   <li><b>ServiceImpl&lt;UserMapper, User&gt;</b> —— MyBatis-Plus 提供的通用 Service 实现基类。
 *       泛型参数1是 Mapper 类型，泛型参数2是实体类型。
 *       继承后自动拥有 save()、getById()、updateById()、removeById()、list() 等全套 CRUD 方法，
 *       方法内部直接调用 UserMapper，不需要自己写 SQL。</li>
 *   <li><b>UserService</b> —— 我们自己定义的 Service 接口，声明了用户模块的业务方法。
 *       实现 implements 后，必须覆写接口中定义的所有方法。</li>
 * </ul>
 *
 * <p><b>为什么注入 BCryptPasswordEncoder 和 StringRedisTemplate？</b></p>
 * <ul>
 *   <li><b>BCryptPasswordEncoder</b> —— Spring Security 提供的密码加密器。
 *       用户注册/新增时，明文密码经过 BCrypt 加密后存入数据库（不可逆）。
 *       用户登录时，用 matches() 方法比对明文和密文，验证密码是否正确。</li>
 *   <li><b>StringRedisTemplate</b> —— Spring Data Redis 提供的操作模板。
 *       登录成功后，把用户信息以 Redis Hash 结构缓存起来，
 *       后续请求只需携带 token，TokenAuthenticationFilter 从 Redis 中取出用户信息放入 ThreadLocal，
 *       实现无状态登录（服务端不存 Session，靠 Redis 存登录态）。</li>
 * </ul>
 *
 * <p><b>this.xxx() 的来源：</b><br>
 * 代码中使用 this.save()、this.getById()、this.list()、this.getOne() 等，
 * 这些方法不是这个类自己写的，而是从父类 ServiceImpl 继承来的。
 * this.save(user) 等价于 userMapper.insert(user)，
 * this.getOne(wrapper) 等价于 userMapper.selectOne(wrapper)，
 * 只是 ServiceImpl 封装了一层，用起来更方便。</p>
 */
@Service
@Slf4j
@SuppressWarnings("null") // 抑制 MyBatis-Plus Lambda 方法引用与空类型分析冲突的误报警告
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * BCrypt 密码加密器
     *
     * <p><b>什么是 BCrypt？</b><br>
     * BCrypt 是一种基于 Blowfish 密码的哈希算法，特点是：
     * 1. 不可逆——无法从密文反推出明文；
     * 2. 自带随机盐——同一个密码每次加密结果都不同（密文里包含盐值）；
     * 3. 可配置强度——cost 参数越大越慢越安全（默认 10，即 2^10=1024 轮迭代）。
     * 这样即使数据库泄露，攻击者也无法还原用户密码。</p>
     *
     * <p>用 final 修饰 + 构造器注入，保证不可变（线程安全），
     * 这是 Spring 推荐的依赖注入方式，比 @Autowired 字段注入更清晰、更利于测试。</p>
     */
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * Redis 操作模板（String 类型专用）
     *
     * <p>用于存储登录用户的 token → 用户信息映射。
     * 数据结构选择 Redis Hash（而非 String），因为一个用户有多个属性（id、username、nickname 等），
     * Hash 可以单独读写某个字段，比把整个对象序列化成 JSON 字符串更灵活。</p>
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 商品 Mapper，用于封禁用户时同步下架其在售商品
     */
    private final ProductMapper productMapper;

    /**
     * 商品详情缓存组件，封禁下架商品后需同步清除缓存
     */
    private final ProductCacheService productCacheService;

    /**
     * 用户-角色关联 Mapper，登录时查询用户拥有的角色编码列表，
     * 写入 Redis 登录态的 roles 字段，供 TokenAuthenticationFilter 构建权限。
     */
    private final UserRoleMapper userRoleMapper;

    /**
     * 构造器注入
     *
     * <p>Spring 启动时发现 UserServiceImpl 需要这些类型的 Bean，
     * 会自动去容器里找到对应的 Bean 并传入。
     * 这就是"构造器注入"——比 @Autowired 字段注入更推荐的方式：
     * 1. 依赖关系一目了然（看构造器参数就知道依赖了什么）；
     * 2. 字段可以声明为 final，保证不可变；
     * 3. 单元测试时可以直接 new 出来传入 mock 对象。</p>
     *
     * @param redisTemplate   Redis 操作模板
     * @param passwordEncoder BCrypt 密码加密器
     * @param productMapper   商品数据访问层
     * @param productCacheService 商品详情缓存组件
     * @param userRoleMapper  用户-角色关联数据访问层
     */
    public UserServiceImpl(StringRedisTemplate redisTemplate, BCryptPasswordEncoder passwordEncoder,
                           ProductMapper productMapper, ProductCacheService productCacheService,
                           UserRoleMapper userRoleMapper) {
        this.stringRedisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate 不能为 null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder 不能为 null");
        this.productMapper = Objects.requireNonNull(productMapper, "productMapper 不能为 null");
        this.productCacheService = Objects.requireNonNull(productCacheService, "productCacheService 不能为 null");
        this.userRoleMapper = Objects.requireNonNull(userRoleMapper, "userRoleMapper 不能为 null");
    }

    // ==================== 管理员：查询所有用户 ====================

    /**
     * 查询所有用户列表（管理员接口）
     *
     * <p><b>流程：</b>查询全部用户 → 逐个转换为 UserVO → 返回列表</p>
     *
     * <p><b>为什么返回 UserVO 而不是 User？</b><br>
     * User 实体对应数据库表结构，可能包含 password 等敏感字段。
     * UserVO 是给前端看的视图对象，只包含前端需要展示的字段，不暴露密码。
     * 这种"实体 → VO"的转换在分层架构中很常见，目的是隔离数据层和展示层。</p>
     *
     * <p><b>stream().map().toList() 是什么？</b><br>
     * Java Stream API 的链式操作：
     * 1. stream() —— 把 List 转成流；
     * 2. map() —— 对流中每个元素做转换（User → UserVO）；
     * 3. toList() —— 收集结果为不可变 List（Java 16+ 的写法，比 collect(Collectors.toList()) 更简洁）。</p>
     *
     * @return 所有用户的 VO 列表
     */
    @Override
    public List<UserVO> getList() {
        log.info("管理员查询所有用户列表");
        // this.list() 继承自 ServiceImpl，内部执行 SELECT * FROM user（自动加 WHERE deleted=0）
        return this.list().stream().map(UserServiceImpl::convertToUserVO).toList();
    }

    // ==================== 管理员：新增用户 ====================

    /**
     * 管理员新增用户
     *
     * <p><b>流程：</b>校验用户名唯一 → 构建 User 实体 → 密码加密 → 保存到数据库</p>
     *
     * <p><b>和 register() 的区别：</b><br>
     * 1. 管理员新增时可以指定手机号等信息，用户注册时这些信息可选；
     * 2. 管理员新增的用户 status 直接设为 1（启用），不需要审核；
     * 3. 管理员新增不设置 role（由数据库默认值或后续分配），注册默认 role=0（普通用户）。</p>
     *
     * @param userAddDTO 新增用户请求参数（用户名、密码、昵称、手机号）
     * @throws BusinessException 用户名已存在时抛出 USER_ALREADY_EXISTS
     */
    @Override
    public void add(UserAddDTO userAddDTO) {
        log.info("管理员新增用户：username={}", userAddDTO.getUsername());

        // 1. 校验用户名是否已存在（唯一性约束）
        //    LambdaQueryWrapper 用 Lambda 引用字段名，比字符串 "username" 更安全——字段改名编译就报错
        //    等价 SQL: SELECT * FROM user WHERE username = ? AND deleted = 0
        String username = userAddDTO.getUsername();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User existUser = this.getOne(wrapper);
        if (existUser != null) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS, "用户名已存在");
        }

        // 2. 构建 User 实体，把 DTO 中的数据拷贝过来
        //    DTO 是前端传来的数据，Entity 是要存入数据库的数据，两者职责不同
        User user = new User();
        user.setUsername(userAddDTO.getUsername());

        // 3. 密码加密：明文 → BCrypt 密文
        //    encode() 内部会生成随机盐并拼接到密文中，每次加密结果都不同
        //    数据库中存的是类似 "$2a$10$N9qo8uLOickgx2ZMRZoMye..." 的字符串
        user.setPassword(passwordEncoder.encode(userAddDTO.getPassword()));

        // 4. 昵称处理：如果没传昵称，默认用用户名作为昵称
        //    StringUtils.hasText() 排除了 null、空字符串、纯空白，比 != null 更严格
        user.setNickname(StringUtils.hasText(userAddDTO.getNickname()) ? userAddDTO.getNickname() : userAddDTO.getUsername());

        // 空字符串转 null：user 表对 phone 有唯一索引，MySQL 允许多个 NULL 但不允许重复空字符串
        user.setPhone(StringUtils.hasText(userAddDTO.getPhone()) ? userAddDTO.getPhone() : null);
        user.setStatus(1);  // 管理员新增的用户默认启用（1=启用，0=禁用）

        // 5. 保存到数据库
        //    this.save() 继承自 ServiceImpl，内部执行 INSERT INTO user (...) VALUES (...)
        //    保存成功后，MyBatis-Plus 会自动把数据库生成的自增 ID 回填到 user.getId()
        this.save(user);
        log.info("管理员新增用户成功：userId={}", user.getId());
    }

    // ==================== 查询用户详情 ====================

    /**
     * 根据 ID 查询用户详情（隐私保护）
     *
     * <p><b>流程：</b>根据 ID 查用户 → 判空 → 按请求者身份返回不同粒度的信息</p>
     *
     * <p><b>为什么要区分身份返回？（隐私关键）</b><br>
     * 旧实现任何登录用户查任意 ID 都能拿到手机号、账号状态、角色等敏感信息，
     * 这是隐私泄露。现在按请求者身份分级：</p>
     * <ul>
     *   <li>查自己 或 管理员 → 返回完整信息（含手机号、状态、角色等）</li>
     *   <li>查他人（普通用户）→ 只返回公开信息（id、昵称、头像），
     *       不暴露手机号、用户名、状态、角色、创建时间</li>
     * </ul>
     *
     * @param id 用户 ID
     * @return 用户视图对象（按请求者身份决定字段粒度）
     * @throws BusinessException 用户不存在时抛出 USER_NOT_FOUND
     */
    @Override
    public UserVO getUserById(Long id) {
        log.info("查询用户详情：userId={}", id);

        // 1. 根据 ID 查询用户（MyBatis-Plus 自动加 WHERE deleted=0，已删除的查不到）
        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "用户不存在");
        }

        // 2. 判断请求者身份：是否是本人或管理员
        UserVO currentUser = UserHolder.getUserVO();
        boolean isSelf = currentUser != null && id.equals(currentUser.getId());
        boolean isAdmin = currentUser != null && currentUser.getRole() != null && currentUser.getRole() >= 1;

        // 3. 本人或管理员 → 返回完整信息；其他人 → 只返回公开信息（保护隐私）
        if (isSelf || isAdmin) {
            return convertToUserVO(user);
        }
        UserVO publicVO = new UserVO();
        publicVO.setId(user.getId());
        publicVO.setNickname(user.getNickname());
        publicVO.setAvatar(user.getAvatar());
        return publicVO;
    }

    // ==================== 用户注册 ====================

    /**
     * 用户注册
     *
     * <p><b>流程：</b>校验用户名唯一 → 构建 User 实体 → 密码加密 → 设置默认角色 → 保存到数据库</p>
     *
     * <p><b>为什么注册时 role 默认设为 0？</b><br>
     * role 字段含义：0=普通用户，1=管理员。
     * 新注册的用户一定是普通用户，不能自己给自己设管理员权限。
     * 如果不显式设置，数据库中该字段为 null，后续登录时 getRole() 会返回 null，
     * 可能导致前端判断权限时出错。所以这里必须显式设置默认值。</p>
     *
     * <p><b>密码为什么要加密存储？</b><br>
     * 如果数据库被拖库（泄露），明文密码直接暴露，用户在其他网站用相同密码也会被盗。
     * BCrypt 加密后即使密文泄露，攻击者也无法还原明文（不可逆 + 随机盐）。</p>
     *
     * @param userRegisterDTO 注册请求参数（用户名、密码、昵称、手机号）
     * @throws BusinessException 用户名已存在时抛出 USER_ALREADY_EXISTS
     */
    @Override
    public void register(UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：username={}", userRegisterDTO.getUsername());

        // 1. 校验用户名是否已存在（唯一性约束）
        //    等价 SQL: SELECT * FROM user WHERE username = ? AND deleted = 0
        String username = userRegisterDTO.getUsername();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User existUser = this.getOne(wrapper);
        if (existUser != null) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        // 2. 构建 User 实体
        User user = new User();
        user.setUsername(username);

        // 3. 密码加密：明文 → BCrypt 密文（不可逆，每次加密结果不同）
        user.setPassword(passwordEncoder.encode(userRegisterDTO.getPassword()));

        // 4. 昵称处理：如果没传昵称，默认用用户名
        if (StringUtils.hasText(userRegisterDTO.getNickname())) {
            user.setNickname(userRegisterDTO.getNickname());
        } else {
            user.setNickname(username);
        }

        // 空字符串转 null：user 表对 phone 有唯一索引，MySQL 允许多个 NULL 但不允许重复空字符串
        user.setPhone(StringUtils.hasText(userRegisterDTO.getPhone()) ? userRegisterDTO.getPhone() : null);
        user.setStatus(1);  // 新注册用户默认启用
        user.setRole(0);    // 新注册用户默认普通用户（0=普通用户，1=管理员）

        // 5. 保存到数据库（INSERT），保存后 user.getId() 自动回填自增 ID
        this.save(user);
        log.info("用户注册成功：userId={}", user.getId());
    }

    // ==================== 用户登录 ====================

    /**
     * 用户登录
     *
     * <p><b>流程（5步）：</b></p>
     * <ol>
     *   <li>根据用户名查询用户</li>
     *   <li>校验密码（BCrypt matches）</li>
     *   <li>校验账号状态（是否被禁用）</li>
     *   <li>生成 token 并把用户信息存入 Redis Hash</li>
     *   <li>返回 token + 用户信息给前端</li>
     * </ol>
     *
     * <p><b>为什么用 Redis 存登录态而不是 Session？</b><br>
     * 传统 Session 存在服务器内存中，多台服务器部署时 Session 不共享（用户在 A 服务器登录，
     * 请求打到 B 服务器就找不到 Session）。Redis 是独立的中间件，所有服务器都能访问，
     * 天然支持分布式部署。而且 Redis 支持设置过期时间（TTL），token 到期自动失效，无需手动清理。</p>
     *
     * <p><b>为什么用 UUID 作为 token？</b><br>
     * UUID（Universally Unique Identifier）是 128 位随机数，碰撞概率极低（约 2^-122）。
     * 去掉中间的横线后变成 32 位十六进制字符串，更紧凑。
     * 攻击者无法猜测或遍历有效的 token，保证了安全性。</p>
     *
     * <p><b>为什么用 Redis Hash 而不是 String（JSON）？</b><br>
     * Hash 结构可以单独读写某个字段（HGET/HSET），不需要每次取出整个对象再反序列化。
     * 比如过滤器只需要取 username 时，直接 HGET key username 即可，性能更好。
     * 而且 Hash 在字段少时比 String 更省内存（Redis 内部用 ziplist 编码）。</p>
     *
     * @param userLoginDTO 登录请求参数（用户名、密码）
     * @return 包含 token 和用户信息的登录结果
     * @throws BusinessException 密码错误或账号被禁用时抛出对应异常
     */
    @Override
    public LoginVO login(UserLoginDTO userLoginDTO) {
        log.info("用户登录：username={}", userLoginDTO.getUsername());

        // ===== 第 1 步：根据用户名查询用户 =====
        // 等价 SQL: SELECT * FROM user WHERE username = ? AND deleted = 0
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, userLoginDTO.getUsername());
        User user = this.getOne(wrapper);

        // ===== 第 2 步：校验密码 =====
        // 两种情况都返回"用户名或密码错误"，不区分"用户不存在"和"密码错误"——
        // 这是安全最佳实践：防止攻击者通过错误信息枚举有效用户名
        //
        // passwordEncoder.matches(明文, 密文) 的工作原理：
        //   1. 从密文中提取盐值（密文格式：$2a$10$盐值+哈希）
        //   2. 用相同的盐值对明文重新加密
        //   3. 比较两次加密结果是否一致
        if (user == null || !passwordEncoder.matches(userLoginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR, "用户名或密码错误");
        }

        // ===== 第 3 步：校验账号状态 =====
        // status: 1=启用，0=禁用（管理员可以禁用违规用户）
        // 先判空再比较，防止数据库中 status 字段为 null 时 NPE
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_ACCOUNT_DISABLED);
        }

        // ===== 第 4 步：生成 token 并把用户信息存入 Redis =====
        UserVO userVO = convertToUserVO(user);

        // 4.1 生成 token：UUID 去掉横线，得到 32 位十六进制字符串
        //     例如："550e8400-e29b-41d4-a716-446655440000" → "550e8400e29b41d4a716446655440000"
        String token = UUID.randomUUID().toString().replace("-", "");

        // 4.2 拼接 Redis key：前缀 + token
        //     例如："login:token:550e8400e29b41d4a716446655440000"
        //     加前缀的好处：方便按前缀批量删除（如清理所有登录态）、避免和其他业务的 key 冲突
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

        // 4.3 构建 Hash 的 field-value 映射
        //     把 UserVO 的各个属性放入 Map，后续一次性写入 Redis Hash
        //     注意：status 和 role 做了判空保护，防止数据库中字段为 null 时 NPE
        //     - status 为 null 时默认当作 1（启用）
        //     - role 为 null 时默认当作 0（普通用户）
        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", userVO.getId().toString());
        userMap.put("username", userVO.getUsername());
        userMap.put("nickname", userVO.getNickname() == null ? "" : userVO.getNickname());
        userMap.put("phone", userVO.getPhone() == null ? "" : userVO.getPhone());
        userMap.put("avatar", userVO.getAvatar() == null ? "" : userVO.getAvatar());
        userMap.put("status", String.valueOf(userVO.getStatus() != null ? userVO.getStatus() : 1));
        userMap.put("role", String.valueOf(userVO.getRole() != null ? userVO.getRole() : 0));

        // 4.3.1 查询用户角色编码列表，拼接成逗号分隔字符串写入 roles 字段
        //       供 TokenAuthenticationFilter 第 9 步读取并构建 Spring Security 权限
        //       例如：["USER", "ADMIN"] → "USER,ADMIN"；无角色时为空字符串
        List<String> roleCodes = userRoleMapper.selectRoleCodesByUserId(userVO.getId());
        userMap.put("roles", String.join(",", roleCodes));

        // 4.4 写入 Redis Hash
        //     opsForHash().putAll() 等价于 Redis 命令：HSET tokenKey id xxx username xxx ...
        //     一次网络往返写入所有字段，比逐个 HSET 性能更好
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // 4.5 设置过期时间（滑动 TTL 的初始值）
        //     Duration.ofMinutes(N) 表示 N 分钟后自动过期
        //     为什么叫"滑动 TTL"？因为过滤器每次验证 token 时会刷新过期时间（续期），
        //     只要用户持续活跃，token 就不会过期；一旦用户长时间不操作，token 自动失效。
        //     这比固定过期时间更友好——不会在用户操作到一半时突然踢出登录。
        stringRedisTemplate.expire(tokenKey, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

        // 4.6 维护用户 Token 反向索引（用于封禁时强制下线）
        //     把当前 token 加入该用户的 token 集合：SADD login:user_tokens:{userId} {token}
        //     封禁用户时就能一次拿到他所有 token 并全部删除。
        //     集合也设置过期时间，避免用户长期不登录后集合永久残留。
        String userTokensKey = RedisConstants.LOGIN_USER_TOKENS_KEY + userVO.getId();
        stringRedisTemplate.opsForSet().add(userTokensKey, token);
        stringRedisTemplate.expire(userTokensKey, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

        // ===== 第 5 步：组装返回结果 =====
        LoginVO loginVO = new LoginVO();
        loginVO.setToken(token);
        loginVO.setUserVO(userVO);
        return loginVO;
    }

    // ==================== 用户退出登录 ====================

    /**
     * 用户退出登录
     *
     * <p><b>流程：</b>校验 token → 去掉 Bearer 前缀 → 从 Redis 删除登录态</p>
     *
     * <p><b>什么是 Bearer 前缀？</b><br>
     * HTTP Authorization 头的标准格式是 "Bearer {token}"（RFC 6750）。
     * 前端发请求时会在 Header 中带上 "Authorization: Bearer 550e8400..."，
     * 后端拿到的是完整的 "Bearer 550e8400..."，需要去掉 "Bearer " 前缀才是真正的 token。</p>
     *
     * <p><b>退出登录的本质是什么？</b><br>
     * 就是删除 Redis 中 token 对应的 Hash 数据。删除后，后续请求携带这个 token
     * 在过滤器中查 Redis 就查不到了，等同于"未登录"状态。
     * 这是一种"服务端主动失效"的方式，比等 token 自然过期更及时。</p>
     *
     * @param token 前端传来的 Authorization 头（可能带 "Bearer " 前缀）
     * @throws BusinessException token 为空或已过期时抛出 UNAUTHORIZED
     */
    @Override
    public void logout(String token) {
        log.info("用户退出登录");

        // 1. 校验 token 非空
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        // 2. 去掉 "Bearer " 前缀（如果有的话）
        //    前端可能传 "Bearer xxx" 也可能直接传 "xxx"，两种都兼容
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);  // "Bearer " 长度为 7
        }

        // 3. 删除前先读出用户 ID（用于后续从反向索引中移除 token）
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Object idObj = stringRedisTemplate.opsForHash().get(tokenKey, "id");

        // 4. 拼接 Redis key 并删除登录态 Hash
        Boolean deleted = stringRedisTemplate.delete(tokenKey);

        // 5. 判断删除结果
        //    delete() 返回 true 表示成功删除了 key，false 表示 key 不存在（已过期或从未登录）
        //    用 Boolean.FALSE.equals() 而不是 !deleted，防止 deleted 为 null 时 NPE
        if (Boolean.FALSE.equals(deleted)) {
            // 日志中不打印完整 tokenKey（包含 token 敏感信息），只打印前缀部分用于排查
            log.warn("退出登录失败，Token 已过期：tokenKeyPrefix={}...{}",
                    RedisConstants.LOGIN_USER_KEY, token.substring(0, Math.min(8, token.length())));
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }

        // 6. 从用户 Token 反向索引中移除该 token（保持索引干净，最佳努力、失败不影响退出）
        if (idObj != null) {
            try {
                stringRedisTemplate.opsForSet().remove(RedisConstants.LOGIN_USER_TOKENS_KEY + idObj, token);
            } catch (Exception e) {
                log.warn("退出登录时清理 Token 反向索引失败（不影响退出）：userId={}", idObj, e);
            }
        }
        log.info("用户退出登录成功");
    }

    /**
     * 修改个人资料（昵称、头像、手机号，部分更新）
     *
     * <p><b>整体流程（4 步）：</b></p>
     * <ol>
     *   <li>从 ThreadLocal 获取当前登录用户 ID，查出用户实体</li>
     *   <li>部分更新：DTO 中非空的字段才覆盖到实体（未传的保持原值）</li>
     *   <li>写回数据库</li>
     *   <li><b>同步更新登录态</b>：刷新 ThreadLocal + Redis Hash，保证后续请求读到新资料</li>
     * </ol>
     *
     * <p><b>为什么要同步更新 Redis 登录态？（关键）</b><br>
     * 本项目的登录态存在 Redis Hash 中（login 时写入）。每次请求，TokenAuthenticationFilter
     * 都是从 Redis 读取用户信息放进 ThreadLocal。如果只改了数据库和 ThreadLocal，
     * 而不改 Redis，那么本次请求结束后 ThreadLocal 被清理，下次请求过滤器从 Redis
     * 读到的还是旧昵称/旧头像——用户会发现自己"改了等于没改"。
     * 所以必须把变更同步到 Redis Hash，登录态才与数据库保持一致。</p>
     *
     * <p><b>什么是"部分更新"？</b><br>
     * 前端可能只改了头像，没传昵称和手机号。DTO 中所有字段都是可选的，
     * 这里逐个判断：字段有值才覆盖，为 null/空就跳过保持原值，
     * 避免把用户没传的字段误置为 null。</p>
     *
     * @param dto   修改参数（所有字段可选，部分更新）
     * @param token 当前登录令牌（用于定位 Redis 中的登录态并同步更新）
     * @throws BusinessException 用户不存在时抛出 USER_NOT_FOUND
     */
    @Override
    public void updateProfile(UserProfileUpdateDTO dto, String token) {
        // ===== 第 1 步：获取当前登录用户并查出实体 =====
        // 从 ThreadLocal 拿用户 ID（由过滤器注入，不可伪造），而不是从参数传，防止越权改别人资料
        Long userId = UserHolder.getUserVO().getId();
        log.info("修改个人资料：userId={}", userId);

        // 根据 ID 查用户（MyBatis-Plus 自动加 WHERE deleted=0，已删除的查不到）
        User existUser = this.getById(userId);
        if (existUser == null) {
            log.warn("修改个人资料失败，用户不存在：userId={}", userId);
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // ===== 第 2 步：部分更新（非空才覆盖）=====
        // String 用 StringUtils.hasText() 判空：排除 null、""、纯空白，比 != null 更严格
        if (StringUtils.hasText(dto.getAvatar())) {
            existUser.setAvatar(dto.getAvatar());
        }
        if (StringUtils.hasText(dto.getNickname())) {
            existUser.setNickname(dto.getNickname());
        }
        // phone：传了空字符串表示"清空手机号"，转成 null 存储
        // 不能存空字符串 ""，因为 user 表对 phone 有唯一索引，MySQL 允许多个 NULL 但不允许重复空字符串
        if (dto.getPhone() != null) {
            existUser.setPhone(StringUtils.hasText(dto.getPhone()) ? dto.getPhone() : null);
        }

        // ===== 第 3 步：写回数据库 =====
        // updateById 只更新实体中被 set 过的字段，未改动的字段不会出现在 SQL 中
        this.updateById(existUser);
        log.info("修改个人资料成功（已写库）：userId={}, nickname={}", userId, existUser.getNickname());

        // ===== 第 4 步：同步更新登录态（ThreadLocal + Redis）=====
        // 4.1 刷新 ThreadLocal：让本次请求后续逻辑（如返回给前端的 /user/me）能立即拿到新资料
        UserVO currentUser = UserHolder.getUserVO();
        currentUser.setNickname(existUser.getNickname());
        currentUser.setAvatar(existUser.getAvatar());
        currentUser.setPhone(existUser.getPhone());

        // 4.2 同步 Redis Hash：保证下次请求过滤器读到的也是最新资料
        refreshLoginUserInRedis(token, existUser);
    }

    /**
     * 把最新的用户资料同步到 Redis 登录态 Hash 中
     *
     * <p><b>为什么抽成独立方法？</b><br>
     * "更新 Redis 登录态"是一段有独立职责的逻辑（去 Bearer 前缀、拼 key、逐字段 HSET），
     * 抽出来后 updateProfile 主流程更清晰，以后若新增"修改手机号需同步登录态"等场景也能复用。</p>
     *
     * <p><b>为什么用 try-catch 包裹？</b><br>
     * 执行到这里时数据库已经更新成功了。Redis 是外部服务，可能因重启、网络抖动等暂时不可用。
     * 如果因为同步 Redis 失败就抛异常，用户会收到 500 错误，以为资料没改成功而重复提交——
     * 但数据库其实已经改好了。所以这里降级处理：Redis 异常只记警告日志，不影响主流程。
     * 最坏情况：登录态 temporarily 仍是旧资料，用户重新登录一次即可刷新。</p>
     *
     * @param token 当前登录令牌（可能带 "Bearer " 前缀）
     * @param user  已更新的用户实体（从中取最新的昵称/头像/手机号）
     */
    private void refreshLoginUserInRedis(String token, User user) {
        try {
            // 去掉 "Bearer " 前缀（与 logout 的处理保持一致），兼容前端两种传法
            if (StringUtils.hasText(token) && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

            // 只更新被修改的 3 个字段（HSET 单字段更新，不影响 Hash 中的 id、username、role 等）
            // 判空保护：字段为 null 时存空字符串，与 login 时的存储约定保持一致
            stringRedisTemplate.opsForHash().put(tokenKey, "nickname",
                    user.getNickname() == null ? "" : user.getNickname());
            stringRedisTemplate.opsForHash().put(tokenKey, "avatar",
                    user.getAvatar() == null ? "" : user.getAvatar());
            stringRedisTemplate.opsForHash().put(tokenKey, "phone",
                    user.getPhone() == null ? "" : user.getPhone());
            log.info("已同步登录态到 Redis：userId={}", user.getId());
        } catch (Exception e) {
            // Redis 异常不影响主流程：数据库已更新成功，用户重新登录即可刷新登录态
            log.warn("同步登录态到 Redis 失败（不影响资料修改，重新登录可刷新）：userId={}", user.getId(), e);
        }
    }

    /**
     * 修改密码（需验证旧密码）
     *
     * <p><b>流程（5 步）：</b></p>
     * <ol>
     *   <li>从 ThreadLocal 获取当前登录用户 ID，查出用户实体（防越权改他人密码）</li>
     *   <li>校验两次输入的新密码是否一致（跨字段校验，DTO 注解无法完成）</li>
     *   <li>校验新密码不能与旧密码相同（防无意义修改）</li>
     *   <li>BCrypt 校验旧密码是否正确</li>
     *   <li>加密新密码并写回数据库</li>
     * </ol>
     *
     * <p><b>为什么从 ThreadLocal 取 userId 而不是从参数传？</b><br>
     * 与 {@link #updateProfile} 同理：userId 由过滤器从 Redis 登录态解析后注入 ThreadLocal，
     * 不可被前端伪造。如果从请求参数传 userId，攻击者可以传别人的 ID 去改别人的密码，
     * 这是严重的越权漏洞。从 ThreadLocal 取能保证"只能改自己的密码"。</p>
     *
     * <p><b>为什么要在 Service 层再校验两次密码一致？</b><br>
     * {@link UserPasswordUpdateDTO} 上的 {@code @NotBlank} 只能校验单字段非空，
     * 无法跨字段比较 newPassword 与 confirmPassword。这种"两个字段之间关系"的校验
     * 必须放在 Service 层手动完成。</p>
     *
     * <p><b>为什么校验新密码不能与旧密码相同？</b><br>
     * 1. 用户体验：如果新旧密码一样，修改密码这个操作毫无意义，应提示用户避免误操作；
     * 2. 安全性：防止用户在"被迫修改密码"场景下用同一个密码糊弄过去。</p>
     *
     * <p><b>为什么不需要同步 Redis 登录态？</b><br>
     * 登录态 Hash 中存的是 id、username、nickname、avatar、phone、status、role，
     * <b>不包含 password</b>。改密码只影响数据库，不影响 Redis 中的登录态字段，
     * 所以无需像 {@link #updateProfile} 那样调用 refreshLoginUserInRedis。
     * 用户改完密码后旧 token 依然有效，无需强制重新登录（如需强制下线可额外删除 token）。</p>
     *
     * @param dto 修改密码参数（旧密码、新密码、确认密码）
     * @throws BusinessException 用户不存在 / 两次密码不一致 / 新旧密码相同 / 旧密码错误
     */
    @Override
    public void updatePassword(UserPasswordUpdateDTO dto) {
        // ===== 第 1 步：获取当前登录用户并查出实体 =====
        // 从 ThreadLocal 拿用户 ID（由过滤器注入，不可伪造），防止越权改他人密码
        Long userId = UserHolder.getUserVO().getId();
        log.info("修改密码：userId={}", userId);

        User existUser = this.getById(userId);
        if (existUser == null) {
            log.warn("修改密码失败，用户不存在：userId={}", userId);
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // ===== 第 2 步：校验两次输入的新密码是否一致 =====
        // 跨字段校验在 Service 层完成（DTO 注解只能校验单字段非空）
        // 用 equals 而非 == ：String 是引用类型，== 比较地址，equals 比较内容
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_NOT_MATCH);
        }

        // ===== 第 3 步：校验新密码不能与旧密码相同 =====
        // 防止无意义修改；此时还没校验旧密码是否正确，但新旧相同就直接拒绝
        if (dto.getNewPassword().equals(dto.getOldPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_SAME);
        }

        // ===== 第 4 步：BCrypt 校验旧密码是否正确 =====
        // matches(明文, 密文)：从密文提取盐值，用相同盐值对明文重新加密后比对
        // 注意：这里比对的是 dto.getOldPassword()（用户输入的明文）与 existUser.getPassword()（数据库密文）
        if (!passwordEncoder.matches(dto.getOldPassword(), existUser.getPassword())) {
            throw new BusinessException(ResultCode.USER_OLD_PASSWORD_ERROR);
        }

        // ===== 第 5 步：加密新密码并写回数据库 =====
        // encode() 每次生成随机盐，即使新密码和某用户原密码相同，密文也完全不同
        existUser.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        // updateById 只更新被 set 过的字段（这里只有 password），其他字段不会出现在 UPDATE SQL 中
        this.updateById(existUser);
        log.info("修改密码成功：userId={}", userId);
    }

    // ==================== 管理员：封禁/解封用户 ====================

    /**
     * 管理员封禁用户
     *
     * <p><b>流程：</b>查用户是否存在 → 校验不能封禁管理员 → 将 status 置为 0 → 写库</p>
     *
     * <p><b>封禁的原理是什么？</b><br>
     * 复用 User 的 status 字段（1=启用，0=禁用）。封禁就是把 status 改为 0。
     * 登录接口的第 3 步会校验 status：如果 status=0 就拒绝登录（抛 USER_ACCOUNT_DISABLED）。
     * 所以被封禁的用户下次登录时会被拦住，无需额外的“黑名单”表。</p>
     *
     * <p><b>为什么不能封禁管理员？</b><br>
     * 防止管理员之间互相封禁导致后台无人可用（尤其是只有一个管理员的场景）。
     * 管理员账号 role=1，通过检查 role 字段来拦截。</p>
     *
     * @param id 要封禁的用户 ID
     * @throws BusinessException 用户不存在 / 目标是管理员
     */
    @Override
    @Transactional // 封禁涉及两步写操作（改用户状态 + 批量下架商品），必须在同一事务中，防止第二步失败导致数据不一致
    public void banUser(Long id) {
        log.info("管理员封禁用户：targetUserId={}", id);

        // 1. 查用户是否存在
        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 2. 校验：不能封禁管理员（role=1）
        if (user.getRole() != null && user.getRole() == 1) {
            log.warn("封禁失败，目标是管理员：targetUserId={}", id);
            throw new BusinessException(ResultCode.CANNOT_BAN_ADMIN);
        }

        // 3. 将 status 置为 0（禁用），被封禁用户下次登录时会被拦截
        user.setStatus(0);
        this.updateById(user);

        // 4. 同步下架该用户的在售/待审核商品
        //    用户被封禁后，其商品不应再被其他人购买或审核通过。
        //    用条件更新一次性把该卖家所有“在售(1)”和“待审核(4)”的商品改为“下架(0)”。
        //    注意：锁定(2，有进行中订单)和已售(3)的商品不动，由订单流程自行处理。
        //    下架前先查出这些商品的 ID，用于下架后清除它们的详情缓存。
        List<Long> takenDownProductIds = productMapper.selectList(
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getSellerId, id)
                        .in(Product::getStatus, ProductStatus.ON_SALE, ProductStatus.PENDING_REVIEW)
                        .select(Product::getId))
                .stream().map(Product::getId).toList();
        
        LambdaUpdateWrapper<Product> productUpdate = new LambdaUpdateWrapper<>();
        productUpdate.eq(Product::getSellerId, id)
                .in(Product::getStatus, ProductStatus.ON_SALE, ProductStatus.PENDING_REVIEW)
                .set(Product::getStatus, ProductStatus.OFF_SALE);
        int takenDown = productMapper.update(null, productUpdate);
        
        // 逐个清除被下架商品的详情缓存，避免用户继续看到旧的“在售”状态
        takenDownProductIds.forEach(productCacheService::evict);

        // 5. 强制下线：删除该用户所有有效的登录态 token
        //    只改数据库 status 是不够的——用户已登录的旧 token 仍在 Redis 中，
        //    在过期前（且滑动过期会不断续期）仍能正常访问接口。
        //    通过反向索引 login:user_tokens:{userId} 拿到他所有 token 并逐个删除，
        //    再删除集合本身，让用户立即下线。
        forceLogoutAll(id);

        // 6. 写入封禁标记：即使有 token 因 Redis 抖动残留，过滤器检查到此标记也会立即拒绝
        //    这是比删除 token 更可靠的“即时生效”手段（不依赖反向索引是否完整）。
        try {
            stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_DISABLED_KEY + id, "1");
        } catch (Exception e) {
            log.warn("写入封禁标记失败（过滤器仍会靠 status 兜底）：userId={}", id, e);
        }

        log.info("封禁用户成功：targetUserId={}, 同步下架商品{}件，已强制下线", id, takenDown);
    }

    /**
     * 强制某用户全部登录态下线（删除其所有 token）
     *
     * <p>通过反向索引 login:user_tokens:{userId} 拿到该用户所有 token，
     * 逐个删除对应的 login:user{token} 登录态 Hash，最后删除集合本身。</p>
     *
     * <p><b>为什么用 try-catch 包裹？</b><br>
     * 封禁的核心动作（改 status、下架商品）已完成。删除 Redis 登录态若因 Redis
     * 抖动失败，不应让整个封禁接口报 500。即使个别 token 残留，
     * 过滤器的防御性校验（拒绝 status=0 的用户）也会兜底，不会漏放。</p>
     *
     * @param userId 要强制下线的用户 ID
     */
    private void forceLogoutAll(Long userId) {
        try {
            String userTokensKey = RedisConstants.LOGIN_USER_TOKENS_KEY + userId;
            Set<String> tokens = stringRedisTemplate.opsForSet().members(userTokensKey);
            if (tokens != null && !tokens.isEmpty()) {
                for (String token : tokens) {
                    stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
                }
            }
            stringRedisTemplate.delete(userTokensKey);
            log.info("已强制用户下线：userId={}, 清理token{}个", userId, tokens == null ? 0 : tokens.size());
        } catch (Exception e) {
            log.warn("强制下线失败（过滤器防御校验会兜底）：userId={}", userId, e);
        }
    }

    /**
     * 管理员解封用户
     *
     * <p><b>流程：</b>查用户是否存在 → 将 status 恢复为 1 → 写库</p>
     *
     * <p>解封后用户即可正常登录使用。</p>
     *
     * @param id 要解封的用户 ID
     * @throws BusinessException 用户不存在
     */
    @Override
    public void unbanUser(Long id) {
        log.info("管理员解封用户：targetUserId={}", id);

        // 1. 查用户是否存在
        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 2. 将 status 恢复为 1（启用）
        user.setStatus(1);
        this.updateById(user);

        // 3. 删除封禁标记，让用户可以正常访问
        try {
            stringRedisTemplate.delete(RedisConstants.LOGIN_DISABLED_KEY + id);
        } catch (Exception e) {
            log.warn("删除封禁标记失败：userId={}", id, e);
        }
        log.info("解封用户成功：targetUserId={}", id);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 实体转 VO（User → UserVO）
     *
     * <p><b>为什么需要转换？</b><br>
     * User 实体对应数据库结构，包含 password 等敏感字段。
     * UserVO 是给前端看的视图对象，只包含前端需要的字段（id、username、nickname 等），
     * 不暴露密码。这种"实体 → VO"的转换在分层架构中很常见，目的是隔离数据层和展示层。</p>
     *
     * <p><b>为什么是 static？</b><br>
     * 这个方法不依赖实例状态（不用 this.xxx），只是纯粹的属性拷贝。
     * 标记 static 后可以通过类名直接调用（UserServiceImpl::convertToUserVO），
     * 也可以作为方法引用传给 stream().map()。</p>
     *
     * @param user 用户实体（从数据库查出的）
     * @return 用户视图对象（不含密码）
     */
    private static UserVO convertToUserVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setRole(user.getRole());
        vo.setCreateTime(user.getCreateTime());
        // 注意：没有 setPassword()——密码绝对不能返回给前端
        return vo;
    }
}
