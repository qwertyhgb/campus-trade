package com.ming.campustrade.service.impl;

import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.vo.LoginVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ming.campustrade.dto.UserAddDTO;
import com.ming.campustrade.dto.UserLoginDTO;
import com.ming.campustrade.dto.UserRegisterDTO;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.service.UserService;
import com.ming.campustrade.vo.UserVO;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *       后续请求只需携带 token，拦截器从 Redis 中取出用户信息放入 ThreadLocal，
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
     * 构造器注入
     *
     * <p>Spring 启动时发现 UserServiceImpl 需要 StringRedisTemplate 和 BCryptPasswordEncoder，
     * 会自动去容器里找到对应的 Bean 并传入。
     * 这就是"构造器注入"——比 @Autowired 字段注入更推荐的方式：
     * 1. 依赖关系一目了然（看构造器参数就知道依赖了什么）；
     * 2. 字段可以声明为 final，保证不可变；
     * 3. 单元测试时可以直接 new 出来传入 mock 对象。</p>
     *
     * @param redisTemplate   Redis 操作模板
     * @param passwordEncoder BCrypt 密码加密器
     */
    public UserServiceImpl(StringRedisTemplate redisTemplate, BCryptPasswordEncoder passwordEncoder) {
        this.stringRedisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate 不能为 null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder 不能为 null");
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

        user.setPhone(userAddDTO.getPhone());
        user.setStatus(1);  // 管理员新增的用户默认启用（1=启用，0=禁用）

        // 5. 保存到数据库
        //    this.save() 继承自 ServiceImpl，内部执行 INSERT INTO user (...) VALUES (...)
        //    保存成功后，MyBatis-Plus 会自动把数据库生成的自增 ID 回填到 user.getId()
        this.save(user);
        log.info("管理员新增用户成功：userId={}", user.getId());
    }

    // ==================== 查询用户详情 ====================

    /**
     * 根据 ID 查询用户详情
     *
     * <p><b>流程：</b>根据 ID 查用户 → 判空 → 转换为 VO 返回</p>
     *
     * @param id 用户 ID
     * @return 用户视图对象
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

        // 2. 实体转 VO，过滤掉 password 等敏感字段
        return convertToUserVO(user);
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

        user.setPhone(userRegisterDTO.getPhone());
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
     * 比如拦截器只需要取 username 时，直接 HGET key username 即可，性能更好。
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

        // 4.4 写入 Redis Hash
        //     opsForHash().putAll() 等价于 Redis 命令：HSET tokenKey id xxx username xxx ...
        //     一次网络往返写入所有字段，比逐个 HSET 性能更好
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // 4.5 设置过期时间（滑动 TTL 的初始值）
        //     Duration.ofMinutes(N) 表示 N 分钟后自动过期
        //     为什么叫"滑动 TTL"？因为拦截器每次验证 token 时会刷新过期时间（续期），
        //     只要用户持续活跃，token 就不会过期；一旦用户长时间不操作，token 自动失效。
        //     这比固定过期时间更友好——不会在用户操作到一半时突然踢出登录。
        stringRedisTemplate.expire(tokenKey, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

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
     * 在拦截器中查 Redis 就查不到了，等同于"未登录"状态。
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

        // 3. 拼接 Redis key 并删除
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Boolean deleted = stringRedisTemplate.delete(tokenKey);

        // 4. 判断删除结果
        //    delete() 返回 true 表示成功删除了 key，false 表示 key 不存在（已过期或从未登录）
        //    用 Boolean.FALSE.equals() 而不是 !deleted，防止 deleted 为 null 时 NPE
        if (Boolean.FALSE.equals(deleted)) {
            // 日志中不打印完整 tokenKey（包含 token 敏感信息），只打印前缀部分用于排查
            log.warn("退出登录失败，Token 已过期：tokenKeyPrefix={}...{}",
                    RedisConstants.LOGIN_USER_KEY, token.substring(0, Math.min(8, token.length())));
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        log.info("用户退出登录成功");
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
