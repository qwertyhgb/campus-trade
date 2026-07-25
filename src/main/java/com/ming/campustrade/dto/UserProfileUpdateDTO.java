package com.ming.campustrade.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户个人资料更新数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于"用户修改自己的个人资料"场景（昵称、手机号、头像）。
 * 使用 DTO 而非 {@code User} 实体接收，是为了精确限定"允许前端修改哪些字段"：</p>
 * <ul>
 *   <li><b>username</b>（登录账号）—— 不允许修改，避免账号体系混乱</li>
 *   <li><b>password</b>（密码）—— 修改密码应走独立的"修改密码"接口（需校验旧密码）</li>
 *   <li><b>role / status</b>（角色、状态）—— 只能由管理员后台修改，普通用户无权篡改</li>
 *   <li><b>id / createTime / deleted</b> —— 内部字段，绝不暴露给前端</li>
 * </ul>
 *
 * <p>所有字段均为<b>可选</b>（部分更新）：前端只传需要修改的字段，未传的字段保持原值。
 * Service 层会逐个判断字段是否为空，非空才覆盖，避免把没传的字段误置为 null。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class UserProfileUpdateDTO {

    /**
     * 用户昵称，可选字段，最长 20 个字符。
     *
     * <p>注意这里没有加 {@code @NotBlank}——因为本 DTO 支持部分更新，
     * 用户可能只想改头像而不改昵称。若加了 @NotBlank，用户不传昵称时会被拦截。
     * 是否覆盖由 Service 层根据"字段是否有值"决定。</p>
     */
    @Size(max = 20, message = "昵称长度不能超过20")
    private String nickname;

    /**
     * 手机号，可选字段。
     *
     * <p>正则 {@code ^$|^1[3-9]\d{9}$} 的含义：要么为空字符串（{@code ^$}，允许不填），
     * 要么是合法的 11 位大陆手机号（以 1 开头、第二位 3~9）。
     * 这样既允许选填，又保证填写时格式正确。</p>
     */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /**
     * 用户头像 URL，可选字段，最长 255 个字符。
     *
     * <p>一般由前端先调用上传接口把图片传到服务器/对象存储，拿到 URL 后再提交到这里。
     * 长度限制 255 与数据库 {@code avatar} 列的 VARCHAR(255) 保持一致，防止超长报错。</p>
     */
    @Size(max = 255, message = "头像URL长度不能超过255")
    private String avatar;
}
