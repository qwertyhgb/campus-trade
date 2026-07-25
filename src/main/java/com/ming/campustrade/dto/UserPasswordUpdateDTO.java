package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户修改密码数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于"用户修改自己的登录密码"场景。与 {@link UserProfileUpdateDTO}（改昵称/头像）分开，
 * 是因为修改密码属于敏感操作，必须额外校验旧密码，安全要求更高，所以单独成一个 DTO。</p>
 *
 * <p><b>为什么需要 oldPassword？</b><br>
 * 修改密码前必须验证用户确实知道当前密码，防止账号被盗用后恶意篡改密码。
 * Service 层会用 BCrypt 的 matches() 比对 oldPassword 与数据库中的密文，
 * 不一致则拒绝修改。</p>
 *
 * <p><b>为什么需要 confirmPassword？</b><br>
 * 让用户输入两次新密码，防止因手误输错（如看错键盘）导致密码被改成自己记不住的值。
 * Service 层会校验 newPassword 与 confirmPassword 是否完全一致。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class UserPasswordUpdateDTO {

    /**
     * 旧密码（当前正在使用的密码），必填。
     *
     * <p>Service 层会用 BCrypt 校验它是否与数据库中的密文匹配，
     * 匹配才允许继续修改，否则抛出"旧密码不正确"异常。</p>
     */
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    /**
     * 新密码，必填，长度 6~20 位。
     *
     * <p>长度规则与注册接口保持一致。注意此处接收的是明文，
     * Service 层会用 BCrypt 加密后再写入数据库，绝不存储明文。</p>
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 20, message = "新密码长度必须在6-20之间")
    private String newPassword;

    /**
     * 确认新密码，必填，必须与 newPassword 完全一致。
     *
     * <p>一致性校验在 Service 层进行（DTO 注解无法跨字段比较），
     * 不一致时抛出"两次输入的密码不一致"异常。</p>
     */
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
}
