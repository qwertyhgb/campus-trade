package com.ming.campustrade.service.impl;

import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.constant.RedisConstants;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.dto.UserAddDTO;
import com.ming.campustrade.dto.UserLoginDTO;
import com.ming.campustrade.dto.UserRegisterDTO;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.mapper.ProductMapper;
import com.ming.campustrade.mapper.UserMapper;
import com.ming.campustrade.mapper.UserRoleMapper;
import com.ming.campustrade.service.ProductCacheService;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.LoginVO;
import com.ming.campustrade.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link UserServiceImpl} 的单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl - 用户服务")
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductCacheService productCacheService;

    @Mock
    private UserRoleMapper userRoleMapper;

    private UserServiceImpl userService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    // ==================== 测试数据 ====================

    private User createUser(Long id, String username, String password, Integer status, Integer role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(password);
        user.setNickname(username);
        user.setStatus(status);
        user.setRole(role);
        return user;
    }

    private UserLoginDTO loginDTO(String username, String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    @BeforeEach
    void setUp() throws Exception {
        userService = new UserServiceImpl(stringRedisTemplate, passwordEncoder, productMapper,
                productCacheService, userRoleMapper);
        Field field = CrudRepository.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(userService, userMapper);
    }

    // ==================== 查询用户列表 ====================

    @Nested
    @DisplayName("getList 查询用户列表")
    class GetList {

        @Test
        @DisplayName("成功返回所有用户 VO 列表")
        void shouldReturnAllUsers() {
            User user1 = createUser(1L, "admin", "pwd", 1, 1);
            User user2 = createUser(2L, "user", "pwd", 1, 0);
            when(userMapper.selectList(any())).thenReturn(List.of(user1, user2));

            List<UserVO> result = userService.getList();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getUsername()).isEqualTo("admin");
        }

        @Test
        @DisplayName("没有用户时返回空列表")
        void shouldReturnEmptyList() {
            when(userMapper.selectList(any())).thenReturn(Collections.emptyList());
            assertThat(userService.getList()).isEmpty();
        }
    }

    // ==================== 查询用户详情 ====================

    @Nested
    @DisplayName("getUserById 查询用户详情")
    class GetUserById {

        @Test
        @DisplayName("本人查看自己：返回完整信息（含用户名）")
        void shouldReturnFullInfoWhenSelf() {
            // 设置当前登录用户为 id=1（查自己）
            UserVO self = new UserVO();
            self.setId(1L);
            self.setRole(0);
            UserHolder.saveUser(self);
            try {
                when(userMapper.selectById(1L)).thenReturn(createUser(1L, "testuser", "pwd", 1, 0));

                UserVO vo = userService.getUserById(1L);

                assertThat(vo.getId()).isEqualTo(1L);
                assertThat(vo.getUsername()).isEqualTo("testuser"); // 本人可见完整信息
            } finally {
                UserHolder.removeUser();
            }
        }

        @Test
        @DisplayName("普通用户查看他人：仅返回公开信息（不含用户名/手机号）")
        void shouldReturnPublicInfoWhenOther() {
            // 设置当前登录用户为 id=2（查别人 id=1）
            UserVO other = new UserVO();
            other.setId(2L);
            other.setRole(0);
            UserHolder.saveUser(other);
            try {
                User target = createUser(1L, "testuser", "pwd", 1, 0);
                target.setNickname("昵称");
                target.setPhone("13800138000");
                when(userMapper.selectById(1L)).thenReturn(target);

                UserVO vo = userService.getUserById(1L);

                assertThat(vo.getId()).isEqualTo(1L);
                assertThat(vo.getNickname()).isEqualTo("昵称"); // 公开字段可见
                assertThat(vo.getUsername()).isNull();           // 用户名不暴露
                assertThat(vo.getPhone()).isNull();              // 手机号不暴露
                assertThat(vo.getRole()).isNull();               // 角色不暴露
            } finally {
                UserHolder.removeUser();
            }
        }

        @Test
        @DisplayName("用户不存在时抛出 USER_NOT_FOUND")
        void shouldThrowWhenNotFound() {
            when(userMapper.selectById(999L)).thenReturn(null);

            assertThatThrownBy(() -> userService.getUserById(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.USER_NOT_FOUND.getCode());
        }
    }

    // ==================== 管理员新增用户 ====================

    @Nested
    @DisplayName("add 管理员新增用户")
    class Add {

        @Test
        @DisplayName("成功新增用户，密码已加密，status 默认 1")
        void shouldAddUserSuccessfully() {
            UserAddDTO dto = new UserAddDTO();
            dto.setUsername("admin_new");
            dto.setPassword("123456");
            dto.setNickname("新昵称");
            dto.setPhone("13800138000");

            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(null);
            when(passwordEncoder.encode("123456")).thenReturn("encoded_pwd");

            userService.add(dto);

            verify(userMapper).insert(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getUsername()).isEqualTo("admin_new");
            assertThat(saved.getPassword()).isEqualTo("encoded_pwd");
            assertThat(saved.getNickname()).isEqualTo("新昵称");
            assertThat(saved.getPhone()).isEqualTo("13800138000");
            assertThat(saved.getStatus()).isOne();
        }

        @Test
        @DisplayName("未传昵称时，默认使用用户名作为昵称")
        void shouldUseUsernameAsDefaultNickname() {
            UserAddDTO dto = new UserAddDTO();
            dto.setUsername("nonickname");
            dto.setPassword("123456");

            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(null);
            when(passwordEncoder.encode("123456")).thenReturn("encoded_pwd");

            userService.add(dto);

            verify(userMapper).insert(userCaptor.capture());
            assertThat(userCaptor.getValue().getNickname()).isEqualTo("nonickname");
        }

        @Test
        @DisplayName("用户名已存在时抛出 USER_ALREADY_EXISTS")
        void shouldThrowWhenUsernameExists() {
            UserAddDTO dto = new UserAddDTO();
            dto.setUsername("existing");
            dto.setPassword("123456");

            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(createUser(1L, "existing", "pwd", 1, 0));

            assertThatThrownBy(() -> userService.add(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.USER_ALREADY_EXISTS.getCode());
            verify(userMapper, never()).insert(any(User.class));
        }
    }

    // ==================== 用户注册 ====================

    @Nested
    @DisplayName("register 用户注册")
    class Register {

        @Test
        @DisplayName("成功注册新用户，role 默认 0，status 默认 1")
        void shouldRegisterSuccessfully() {
            UserRegisterDTO dto = new UserRegisterDTO();
            dto.setUsername("newuser");
            dto.setPassword("123456");

            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(null);
            when(passwordEncoder.encode(dto.getPassword())).thenReturn("encoded_pwd");

            userService.register(dto);

            verify(userMapper).insert(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getUsername()).isEqualTo("newuser");
            assertThat(saved.getPassword()).isEqualTo("encoded_pwd");
            assertThat(saved.getRole()).isZero();
            assertThat(saved.getStatus()).isOne();
            assertThat(saved.getNickname()).isEqualTo("newuser");
        }

        @Test
        @DisplayName("用户名已存在时抛出 USER_ALREADY_EXISTS")
        void shouldThrowWhenUsernameExists() {
            UserRegisterDTO dto = new UserRegisterDTO();
            dto.setUsername("existing");
            dto.setPassword("123456");

            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(createUser(1L, "existing", "pwd", 1, 0));

            assertThatThrownBy(() -> userService.register(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.USER_ALREADY_EXISTS.getCode());
            verify(userMapper, never()).insert(any(User.class));
        }
    }

    // ==================== 用户登录 ====================

    @Nested
    @DisplayName("login 用户登录")
    class Login {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("成功登录，返回 LoginVO")
        void shouldLoginSuccessfully() {
            UserLoginDTO dto = loginDTO("user1", "password123");
            User user = createUser(1L, "user1", "encoded_pwd", 1, 0);
            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(user);
            when(passwordEncoder.matches("password123", "encoded_pwd")).thenReturn(true);

            HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
            when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
            // 登录还会维护 token 反向索引（opsForSet）
            SetOperations<String, String> setOps = mock(SetOperations.class);
            when(stringRedisTemplate.opsForSet()).thenReturn(setOps);

            LoginVO loginVO = userService.login(dto);

            assertThat(loginVO).isNotNull();
            assertThat(loginVO.getToken()).isNotBlank();
            assertThat(loginVO.getUserVO()).isNotNull();
            assertThat(loginVO.getUserVO().getId()).isEqualTo(1L);
            assertThat(loginVO.getUserVO().getUsername()).isEqualTo("user1");

            verify(stringRedisTemplate).opsForHash();
            // expire 调用两次：token 登录态 + token 反向索引集合
            verify(stringRedisTemplate, times(2)).expire(anyString(), any(Duration.class));
            // token 被加入反向索引
            verify(setOps).add(anyString(), any());
        }

        @Test
        @DisplayName("密码错误时抛出 USER_PASSWORD_ERROR")
        void shouldThrowWhenPasswordWrong() {
            UserLoginDTO dto = loginDTO("user1", "wrong_pwd");
            User user = createUser(1L, "user1", "encoded_pwd", 1, 0);
            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(user);
            when(passwordEncoder.matches("wrong_pwd", "encoded_pwd")).thenReturn(false);

            assertThatThrownBy(() -> userService.login(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.USER_PASSWORD_ERROR.getCode());
        }

        @Test
        @DisplayName("用户不存在时抛出 USER_PASSWORD_ERROR（不暴露用户是否存在）")
        void shouldThrowWhenUserNotFound() {
            UserLoginDTO dto = loginDTO("nonexistent", "pwd");
            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(null);

            assertThatThrownBy(() -> userService.login(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.USER_PASSWORD_ERROR.getCode());
        }

        @Test
        @DisplayName("账号被禁用时抛出 USER_ACCOUNT_DISABLED")
        void shouldThrowWhenAccountDisabled() {
            UserLoginDTO dto = loginDTO("disabled_user", "pwd");
            User user = createUser(1L, "disabled_user", "encoded_pwd", 0, 0);
            when(userMapper.selectOne(any(), anyBoolean())).thenReturn(user);
            when(passwordEncoder.matches("pwd", "encoded_pwd")).thenReturn(true);

            assertThatThrownBy(() -> userService.login(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.USER_ACCOUNT_DISABLED.getCode());
        }
    }

    // ==================== 退出登录 ====================

    @Nested
    @DisplayName("logout 退出登录")
    class Logout {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("成功退出，删除 Redis token 并清理反向索引")
        void shouldLogoutSuccessfully() {
            // logout 会先从 Hash 读出用户 ID，用于清理反向索引
            HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
            when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
            when(hashOps.get(anyString(), any())).thenReturn("1");
            SetOperations<String, String> setOps = mock(SetOperations.class);
            when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
            when(stringRedisTemplate.delete(anyString())).thenReturn(true);

            userService.logout("some-token");

            verify(stringRedisTemplate).delete(RedisConstants.LOGIN_USER_KEY + "some-token");
            // 从反向索引中移除该 token
            verify(setOps).remove(eq(RedisConstants.LOGIN_USER_TOKENS_KEY + "1"), eq("some-token"));
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("带 Bearer 前缀时正确去除并删除 token")
        void shouldStripBearerPrefix() {
            HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
            when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
            when(hashOps.get(anyString(), any())).thenReturn(null); // 读不到 ID 则跳过反向索引清理
            when(stringRedisTemplate.delete(anyString())).thenReturn(true);

            userService.logout("Bearer my-token-123");

            verify(stringRedisTemplate).delete(RedisConstants.LOGIN_USER_KEY + "my-token-123");
        }

        @Test
        @DisplayName("token 为空时抛出 UNAUTHORIZED")
        void shouldThrowWhenTokenIsEmpty() {
            assertThatThrownBy(() -> userService.logout(""))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.UNAUTHORIZED.getCode());

            verify(stringRedisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("token 为 null 时抛出 UNAUTHORIZED")
        void shouldThrowWhenTokenIsNull() {
            assertThatThrownBy(() -> userService.logout(null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.UNAUTHORIZED.getCode());
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("token 已过期（Redis 中不存在）时抛出 UNAUTHORIZED")
        void shouldThrowWhenTokenAlreadyExpired() {
            HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
            when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
            when(hashOps.get(anyString(), any())).thenReturn(null);
            when(stringRedisTemplate.delete(anyString())).thenReturn(false);

            assertThatThrownBy(() -> userService.logout("expired-token"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", ResultCode.UNAUTHORIZED.getCode());
        }
    }
}
