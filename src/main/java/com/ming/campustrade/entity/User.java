package com.ming.campustrade.entity;

import lombok.Data;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;

@Data
public class User {

    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String phone;

    private String avatar;

    private Integer status;

    private Integer role;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
