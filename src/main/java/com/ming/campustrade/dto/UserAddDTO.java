package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员新增用户数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于后台管理员手动添加用户的场景（区别于用户自助注册的 {@link UserRegisterDTO}）。
 * 使用 DTO 可以精确限定允许提交的字段，防止越权篡改 id、role 等内部字段，
 * 并在入口处完成参数校验。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class UserAddDTO {

    /**
     * 登录用户名，长度 2~10 位，不能为空。
     */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 10, message = "用户名长度必须在2到10位之间")
    private String username;

    /**
     * 登录密码，长度 6~18 位，不能为空。Service 层需加密后再入库。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 18, message = "密码长度必须在6到18位之间")
    private String password;

    /**
     * 用户昵称，可选字段，长度 2~10 位。
     */
    @Size(min = 2, max = 10, message = "昵称长度必须在2到10位之间")
    private String nickname;

    /**
     * 手机号。
     *
     * <p>正则 {@code ^[1][3-9]\d{9}$} 要求必须是合法的 11 位大陆手机号
     * （以 1 开头、第二位 3~9）。注意此处没有像注册接口那样允许为空，
     * 若该字段允许选填，可改为 {@code ^$|^1[3-9]\d{9}$}。</p>
     */
    @Pattern(regexp = "^[1][3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
