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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@SuppressWarnings("null")
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final BCryptPasswordEncoder passwordEncoder;

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate redisTemplate, BCryptPasswordEncoder passwordEncoder) {
        this.stringRedisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public List<UserVO> getList() {
        log.info("管理员查询所有用户列表");

        return this.list().stream().map(UserServiceImpl::convertTUserVO).toList();
    }

    @Override
    public void add(UserAddDTO userAddDTO) {
        log.info("管理员新增用户：username={}", userAddDTO.getUsername());
        String username = userAddDTO.getUsername();

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User existUser = this.getOne(wrapper);
        if (existUser != null) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS, "用户名已存在");
        }

        User user = new User();
        user.setUsername(userAddDTO.getUsername());
        user.setPassword(passwordEncoder.encode(userAddDTO.getPassword()));
        // if (StringUtils.hasText(userAddDTO.getNickname())) {
        //     user.setNickname(userAddDTO.getNickname());
        // } else {
        //     user.setNickname(userAddDTO.getUsername());
        // }
        user.setNickname(StringUtils.hasText(userAddDTO.getNickname()) ? userAddDTO.getNickname() : userAddDTO.getUsername());
        user.setPhone(userAddDTO.getPhone());
        user.setStatus(1);
        this.save(user);
        log.info("管理员新增用户成功：userId={}", user.getId());
    }

    @Override
    public UserVO getUserById(Long id) {
        log.info("查询用户详情：userId={}", id);

        User user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "用户不存在");
        }

        return convertTUserVO(user);
    }

    @Override
    public void register(UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：username={}", userRegisterDTO.getUsername());
        String username = userRegisterDTO.getUsername();

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User exisUser = this.getOne(wrapper);
        if (exisUser != null) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(userRegisterDTO.getPassword()));

        if (StringUtils.hasText(userRegisterDTO.getNickname())) {
            user.setNickname(userRegisterDTO.getNickname());
        } else {
            user.setNickname(username);
        }

        user.setPhone(userRegisterDTO.getPhone());
        user.setStatus(1);

        this.save(user);
        log.info("用户注册成功：userId={}", user.getId());
    }

    @Override
    public LoginVO login(UserLoginDTO userLoginDTO) {
        log.info("用户登录：username={}", userLoginDTO.getUsername());
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, userLoginDTO.getUsername());

        User user = this.getOne(wrapper);
        // if (user == null) {
        //     throw new BusinessException(ResultCode.USER_NOT_FOUND);
        // }

        // if (!passwordEncoder.matches(userLoginDTO.getPassword(), user.getPassword())) {
        //     throw new BusinessException(ResultCode.USER_PASSWORD_ERROR, "密码错误");
        // }

        if (user == null || !passwordEncoder.matches(userLoginDTO.getPassword(), user.getPassword())) {
            throw new  BusinessException(ResultCode.USER_PASSWORD_ERROR, "用户名或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_ACCOUNT_DISABLED);
        }

        UserVO userVO = convertTUserVO(user);

        String token = UUID.randomUUID().toString().replace("-", "");

        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", userVO.getId().toString());
        userMap.put("username", userVO.getUsername());
        userMap.put("nickname", userVO.getNickname() == null ? "" : userVO.getNickname());
        userMap.put("phone", userVO.getPhone() == null ? "" : userVO.getPhone());
        userMap.put("avatar", userVO.getAvatar() == null ? "" : userVO.getAvatar());
        userMap.put("status", userVO.getStatus().toString());
        userMap.put("role", userVO.getRole().toString());

        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

        LoginVO loginVO = new LoginVO();
        loginVO.setToken(token);
        loginVO.setUserVO(userVO);

        return loginVO;
    }

    @Override
    public void logout(String token) {
        log.info("用户退出登录");
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

        Boolean deleted = stringRedisTemplate.delete(tokenKey);

        if (Boolean.FALSE.equals(deleted)) {
            log.warn("退出登录失败，Token 已过期：tokenKey={}", tokenKey);
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        log.info("用户退出登录成功");
    }

    private static UserVO convertTUserVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setRole(user.getRole());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
