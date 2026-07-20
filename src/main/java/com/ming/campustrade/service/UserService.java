package com.ming.campustrade.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.UserAddDTO;
import com.ming.campustrade.dto.UserLoginDTO;
import com.ming.campustrade.dto.UserRegisterDTO;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.vo.LoginVO;
import com.ming.campustrade.vo.UserVO;

/**
 * 用户业务逻辑接口（Service 层）。
 *
 * <p>Service 层位于 Controller（控制层）与 Mapper（数据访问层）之间，
 * 负责编排业务逻辑：参数校验后的处理、密码加密、Token 生成、实体与 VO/DTO 的转换等。
 * 把业务逻辑放在接口里，再由实现类（{@code UserServiceImpl}）完成，
 * 是面向接口编程的惯例——便于解耦、测试与替换实现。</p>
 *
 * <p>继承 MyBatis-Plus 的 {@link IService} 后，本接口自动获得一套针对 {@link User} 的
 * 通用 CRUD 能力（如 {@code save}、{@code removeById}、{@code getById}、{@code list} 等），
 * 它在 {@code BaseMapper} 之上又封装了批量操作和事务支持，因此简单增删改查无需再声明方法。
 * 下面只声明本项目特有的业务方法。</p>
 *
 * @author ming
 */
public interface UserService extends IService<User> {

    /**
     * 查询全部用户列表（通常用于后台管理）。
     *
     * @return 用户视图对象列表（已过滤密码等敏感字段）
     */
    List<UserVO> getList();

    /**
     * 根据 ID 查询单个用户。
     *
     * @param id 用户主键 ID
     * @return 用户视图对象；用户不存在时由实现类决定返回 null 或抛异常
     */
    UserVO getUserById(Long id);

    /**
     * 用户自助注册。
     *
     * <p>实现时需校验用户名是否已存在，并对密码加密后再入库。</p>
     *
     * @param userRegisterDTO 注册参数（已通过 {@code @Valid} 校验）
     */
    void register(UserRegisterDTO userRegisterDTO);

    /**
     * 用户登录。
     *
     * <p>实现时校验账号密码，成功后生成 Token 并连同用户信息一起返回。</p>
     *
     * @param userLoginDTO 登录参数（用户名 + 密码）
     * @return 登录结果，包含 Token 与用户基本信息
     */
    LoginVO login(UserLoginDTO userLoginDTO);

    /**
     * 退出登录，使指定 Token 失效。
     *
     * @param token 当前登录令牌
     */
    void logout(String token);

    /**
     * 管理员后台新增用户。
     *
     * @param userAddDTO 新增用户参数
     */
    void add(UserAddDTO userAddDTO);
}
