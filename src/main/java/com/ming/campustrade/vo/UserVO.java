package com.ming.campustrade.vo;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 用户视图对象（VO，View Object），用于向前端返回用户信息。
 *
 * <p>为什么不直接把 {@code User} 实体返回给前端？
 * 因为实体类与数据库表一一对应，里面包含 {@code password}（密码）、{@code deleted}
 * （逻辑删除标志）等敏感或无意义的内部字段。如果直接返回实体：
 * <ul>
 *     <li>会把加密后的密码也暴露给前端，存在安全隐患；</li>
 *     <li>会泄露数据库内部结构，增加被攻击的风险；</li>
 *     <li>前端会收到一堆它根本不需要的字段，浪费带宽。</li>
 * </ul>
 * 因此我们专门定义 VO，只挑选前端真正需要的字段进行返回，
 * 起到"数据过滤"和"接口与数据库解耦"的作用。</p>
 *
 * @author ming
 */
@Data
public class UserVO {

    /** 用户主键 ID。 */
    private Long id;

    /** 登录用户名（账号）。 */
    private String username;

    /** 用户昵称，用于页面展示。 */
    private String nickname;

    /** 手机号。 */
    private String phone;

    /** 用户头像 URL。 */
    private String avatar;

    /** 账号状态：1=正常，0=禁用。 */
    private Integer status;

    /** 用户角色：0=普通用户，1=管理员。 */
    private Integer role;

    /** 账号创建时间。 */
    private LocalDateTime createTime;
}
