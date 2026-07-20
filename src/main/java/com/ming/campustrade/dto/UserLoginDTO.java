package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户登录数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于接收前端登录表单提交的用户名和密码。使用 DTO 而非实体接收，
 * 是为了只暴露登录所需的字段，并通过校验注解在入口处拦截非法输入，
 * 避免无效请求进入业务逻辑层。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class UserLoginDTO {

    /**
     * 登录用户名。
     *
     * <p>{@link NotBlank} 拦截 null 与纯空格输入；{@link Size} 限制长度 3~20，
     * 与注册时的规则保持一致。</p>
     */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度必须在3-20之间")
    private String username;

    /**
     * 登录密码，长度 6~20 位。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在6-20之间")
    private String password;
}
