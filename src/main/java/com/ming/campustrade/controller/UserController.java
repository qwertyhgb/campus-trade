package com.ming.campustrade.controller;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.dto.UserAddDTO;
import com.ming.campustrade.dto.UserLoginDTO;
import com.ming.campustrade.dto.UserPasswordUpdateDTO;
import com.ming.campustrade.dto.UserProfileUpdateDTO;
import com.ming.campustrade.dto.UserRegisterDTO;
import com.ming.campustrade.service.UserService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.LoginVO;
import com.ming.campustrade.vo.UserVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理控制器 —— 处理用户注册、登录、信息查询、管理员操作等 HTTP 请求。
 *
 * <h2>核心注解说明</h2>
 * <ul>
 *   <li>{@code @RestController}：等价于 {@code @Controller + @ResponseBody}。
 *       标注后，该类中所有方法的返回值都会被自动序列化为 JSON 写入响应体，
 *       不需要在每个方法上单独加 {@code @ResponseBody}。
 *       这是 RESTful API 开发的标准写法。</li>
 *   <li>{@code @RequestMapping("/user")}：为该控制器下所有接口设置「基础路径前缀」。
 *       例如方法上映射 {@code @PostMapping("/register")}，
 *       最终完整路径就是 {@code POST /user/register}。</li>
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
 *   <li>公开接口由 {@code SecurityConfig} 的 {@code permitAll()} 规则统一配置</li>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")}：仅管理员可访问（Spring Security 方法级安全）</li>
 *   <li>无注解：需要登录（由 Security 的 authenticated() 拦截），但不限制角色</li>
 * </ul>
 *
 * @author Ming
 * @since 1.0.0
 */
@Slf4j
@Tag(name = "用户管理", description = "用户的注册、登录、信息查询、管理员操作等")
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    /**
     * 构造器注入：Spring 启动时自动把 UserService 的实现类实例传进来。
     * 因为只有一个构造器，所以不需要额外加 @Autowired 注解（Spring 4.3+ 特性）。
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户注册（公开接口，无需登录）。
     *
     * <p>公开规则由 {@code SecurityConfig} 配置，因为注册时用户还没有账号。</p>
     * <p>{@code @Valid} 触发 Jakarta Validation 校验：DTO 上的 @NotBlank、@Size 等注解
     * 会在此处自动生效，校验不通过会直接返回 400 错误，不会进入方法体。</p>
     * <p>{@code @RequestBody} 表示从请求体中读取 JSON 并反序列化为 Java 对象。</p>
     *
     * @param userRegisterDTO 注册信息（用户名、密码、昵称等）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "用户注册", description = "新用户注册账号（公开接口）")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：username={}", userRegisterDTO.getUsername());
        userService.register(userRegisterDTO);
        log.info("用户注册成功：username={}", userRegisterDTO.getUsername());
        return Result.success();
    }

    /**
     * 用户登录（公开接口，无需登录）。
     *
     * <p>登录成功后返回 token + 用户基本信息，前端需要把 token 存起来，
     * 后续每次请求都在 Header 中携带 {@code Authorization: <token>}。</p>
     *
     * @param userLoginDTO 登录凭证（用户名 + 密码）
     * @return 包含 token 和用户信息的 LoginVO
     */
    @Operation(summary = "用户登录", description = "用户登录，返回 token 和用户基本信息（公开接口）")
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid UserLoginDTO userLoginDTO) {
        log.info("用户登录：username={}", userLoginDTO.getUsername());
        LoginVO loginVO = userService.login(userLoginDTO);
        log.info("用户登录成功：username={}, userId={}", userLoginDTO.getUsername(), loginVO.getUserVO().getId());
        return Result.success(loginVO);
    }

    /**
     * 退出登录（需要登录）。
     *
     * <p>{@code @RequestHeader("Authorization")} 从请求头中提取 token 字符串，
     * 然后让 Service 层把这个 token 从 Redis 中删除，使其立即失效。</p>
     *
     * @param token 请求头中的认证令牌
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "退出登录", description = "使当前 token 失效，退出登录状态")
    @PostMapping("/logout")
    public Result<Void> logout(@Parameter(description = "认证令牌") @RequestHeader("Authorization") String token) {
        log.info("用户退出登录");
        userService.logout(token);
        log.info("用户退出登录成功");
        return Result.success();
    }

    /**
     * 获取所有用户列表（仅管理员）。
     *
     * <p>{@code @PreAuthorize("hasRole('ADMIN')")} 表示需要 ADMIN 角色，
     * 普通用户访问会被 Spring Security 拦截并返回 403。</p>
     *
     * @return 所有用户的列表
     */
    @Operation(summary = "用户列表", description = "获取所有用户列表（仅管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/list")
    public Result<List<UserVO>> list() {
        log.info("管理员查询用户列表");
        List<UserVO> userVOList = userService.getList();
        log.info("查询用户列表成功，共 {} 条", userVOList.size());
        return Result.success(userVOList);
    }

    /**
     * 管理员新增用户（仅管理员）。
     *
     * <p>与注册接口的区别：这是管理员后台操作，可以指定角色等额外字段。</p>
     *
     * @param userAddDTO 新增用户的信息
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "新增用户", description = "管理员新增用户（仅管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/add")
    public Result<Void> add(@RequestBody @Valid UserAddDTO userAddDTO) {
        log.info("管理员新增用户：username={}", userAddDTO.getUsername());
        userService.add(userAddDTO);
        log.info("管理员新增用户成功：username={}", userAddDTO.getUsername());
        return Result.success();
    }

    /**
     * 查询用户详情（需要登录）。
     *
     * <p>{@code @PathVariable} 从 URL 路径中提取变量：
     * 例如请求 GET /user/42，则 id = 42。</p>
     *
     * @param id 用户ID（路径变量）
     * @return 用户详细信息
     */
    @Operation(summary = "查询用户详情", description = "根据用户ID获取用户详细信息")
    @GetMapping("/{id}")
    public Result<UserVO> getUserById(@Parameter(description = "用户ID") @PathVariable Long id) {
        log.info("查询用户详情：userId={}", id);
        return Result.success(userService.getUserById(id));
    }

    /**
     * 获取当前登录用户信息（需要登录）。
     *
     * <p>从 ThreadLocal（UserHolder）中取出当前用户，
     * 这个值是 TokenAuthenticationFilter 根据 token 查出来并存入的。</p>
     *
     * @return 当前登录用户的信息
     */
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的个人信息")
    @GetMapping("/me")
    public Result<UserVO> me() {
        UserVO userVO = UserHolder.getUserVO();
        log.info("获取当前用户信息：userId={}, username={}", userVO.getId(), userVO.getUsername());
        return Result.success(userVO);
    }

    /**
     * 修改个人资料（需要登录）。
     *
     * <p>支持部分更新：昵称、头像、手机号均为可选，只传需要修改的字段。
     * 修改后服务端会同步更新 Redis 中的登录态，无需重新登录即可生效。</p>
     *
     * <p>{@code @RequestHeader("Authorization")} 从请求头提取 token，
     * 传给 Service 层用于定位并同步 Redis 登录态（与 logout 的做法一致）。</p>
     *
     * @param dto   修改参数（昵称、头像、手机号，均可选）
     * @param token 请求头中的认证令牌
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "修改个人资料", description = "修改当前登录用户的昵称、头像、手机号（部分更新），同步刷新登录态")
    @PutMapping("/profile")
    public Result<Void> updateProfile(@RequestBody @Valid UserProfileUpdateDTO dto,
                                      @Parameter(description = "认证令牌") @RequestHeader("Authorization") String token) {
        log.info("修改个人资料");
        userService.updateProfile(dto, token);
        log.info("修改个人资料成功");
        return Result.success();
    }

    /**
     * 修改密码（需要登录）。
     *
     * <p>需要提供旧密码进行身份核验，新密码和确认密码需一致。
     * 修改成功后无需重新登录（当前 token 依然有效，因为 token 与密码无关）。</p>
     *
     * @param dto 修改密码参数（旧密码、新密码、确认密码）
     * @return 统一响应结果（无数据体）
     */
    @Operation(summary = "修改密码", description = "修改当前登录用户的登录密码，需验证旧密码")
    @PutMapping("/password")
    public Result<Void> updatePassword(@RequestBody @Valid UserPasswordUpdateDTO dto) {
        log.info("修改密码");
        userService.updatePassword(dto);
        log.info("修改密码成功");
        return Result.success();
    }
}
