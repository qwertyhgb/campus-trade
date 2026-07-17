package com.ming.campustrade.controller;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.annotation.PublicApi;
import com.ming.campustrade.common.annotation.RequireRole;
import com.ming.campustrade.dto.UserAddDTO;
import com.ming.campustrade.dto.UserLoginDTO;
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

import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "用户管理", description = "用户的注册、登录、信息查询、管理员操作等")
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "用户注册", description = "新用户注册账号（公开接口）")
    @PublicApi
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：username={}", userRegisterDTO.getUsername());
        userService.register(userRegisterDTO);
        log.info("用户注册成功：username={}", userRegisterDTO.getUsername());
        return Result.success();
    }

    @Operation(summary = "用户登录", description = "用户登录，返回 token 和用户基本信息（公开接口）")
    @PublicApi
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid UserLoginDTO userLoginDTO) {
        log.info("用户登录：username={}", userLoginDTO.getUsername());
        LoginVO loginVO = userService.login(userLoginDTO);
        log.info("用户登录成功：username={}, userId={}", userLoginDTO.getUsername(), loginVO.getUserVO().getId());
        return Result.success(loginVO);
    }

    @Operation(summary = "退出登录", description = "使当前 token 失效，退出登录状态")
    @PostMapping("/logout")
    public Result<Void> logout(@Parameter(description = "认证令牌") @RequestHeader("Authorization") String token) {
        log.info("用户退出登录");
        userService.logout(token);
        log.info("用户退出登录成功");
        return Result.success();
    }

    @Operation(summary = "用户列表", description = "获取所有用户列表（仅管理员）")
    @RequireRole(1)
    @GetMapping("/list")
    public Result<List<UserVO>> list() {
        log.info("管理员查询用户列表");
        List<UserVO> userVOList = userService.getList();
        log.info("查询用户列表成功，共 {} 条", userVOList.size());
        return Result.success(userVOList);
    }

    @Operation(summary = "新增用户", description = "管理员新增用户（仅管理员）")
    @RequireRole(1)
    @PostMapping("/add")
    public Result<Void> add(@RequestBody @Valid UserAddDTO userAddDTO) {
        log.info("管理员新增用户：username={}", userAddDTO.getUsername());
        userService.add(userAddDTO);
        log.info("管理员新增用户成功：username={}", userAddDTO.getUsername());
        return Result.success();
    }

    @Operation(summary = "查询用户详情", description = "根据用户ID获取用户详细信息")
    @GetMapping("/{id}")
    public Result<UserVO> getUserById(@Parameter(description = "用户ID") @PathVariable Long id) {
        log.info("查询用户详情：userId={}", id);
        return Result.success(userService.getUserById(id));
    }

    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的个人信息")
    @GetMapping("/me")
    public Result<UserVO> me() {
        UserVO userVO = UserHolder.getUserVO();
        log.info("获取当前用户信息：userId={}, username={}", userVO.getId(), userVO.getUsername());
        return Result.success(userVO);
    }
}
