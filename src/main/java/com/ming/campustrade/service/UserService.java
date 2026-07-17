package com.ming.campustrade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ming.campustrade.dto.UserAddDTO;
import com.ming.campustrade.dto.UserLoginDTO;
import com.ming.campustrade.dto.UserRegisterDTO;
import com.ming.campustrade.entity.User;
import com.ming.campustrade.vo.LoginVO;
import com.ming.campustrade.vo.UserVO;

import java.util.List;


public interface UserService extends IService<User> {

    List<UserVO> getList();

    UserVO getUserById(Long id);

    void register(UserRegisterDTO userRegisterDTO);

    LoginVO login(UserLoginDTO userLoginDTO);

    void logout(String token);

    void add(UserAddDTO userAddDTO);
}
