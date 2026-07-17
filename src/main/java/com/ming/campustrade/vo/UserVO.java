package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UserVO {
    private Long id;

    private String username;

    private String nickname;

    private String phone;

    private String avatar;

    private Integer status;

    private Integer role;

    private LocalDateTime createTime;
}
