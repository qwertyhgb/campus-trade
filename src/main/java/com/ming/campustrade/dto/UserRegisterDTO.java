package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户注册数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>DTO 用于"接收"前端传来的请求参数。为什么不直接用 {@code User} 实体接收？
 * 因为注册时前端只应提交用户名、密码等有限字段，而实体里还有 id、createTime、
 * role 等字段——若直接用实体接收，恶意用户可能伪造 role=1 把自己注册成管理员。
 * 用 DTO 可以精确控制"允许前端传入哪些字段"，并在字段上声明校验规则。</p>
 *
 * <p>字段上的 {@code @NotBlank}、{@code @Size}、{@code @Pattern} 等是
 * Jakarta Bean Validation 校验注解，配合 Controller 方法参数上的 {@code @Valid}
 * 使用，框架会在进入业务逻辑前自动校验，不合法时直接返回 {@code message} 提示，
 * 避免把脏数据写进数据库。这里使用 {@code jakarta.*} 包（Spring Boot 3 / Java 21 标准），
 * 而非已废弃的 {@code javax.*}。</p>
 *
 * @author ming
 */
@Data
public class UserRegisterDTO {

    /**
     * 登录用户名。
     *
     * <p>{@link NotBlank} 要求不能为 null 且去除首尾空格后不能为空字符串
     * （比 {@code @NotEmpty} 更严格，能拦截 "   " 这种纯空格输入）；
     * {@link Size} 限制长度在 3~20 之间，过短易冲突、过长无意义。</p>
     */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度必须在3-20之间")
    private String username;

    /**
     * 登录密码，长度 6~20 位。注意此处接收的是明文，Service 层需加密后再入库。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在6-20之间")
    private String password;

    /**
     * 用户昵称，可选字段，最长 20 个字符。
     */
    @Size(max = 20, message = "昵称长度不能超过20")
    private String nickname;

    /**
     * 手机号，可选字段。
     *
     * <p>正则 {@code ^$|^1[3-9]\d{9}$} 的含义：要么为空字符串（{@code ^$}，允许不填），
     * 要么是合法的 11 位大陆手机号（以 1 开头、第二位 3~9）。这样既允许选填，又保证填写时格式正确。</p>
     */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
