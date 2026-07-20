package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Data;

/**
 * 用户实体类（Entity）。
 *
 * <p>本类与数据库中的 {@code user} 表一一映射：类名 {@code User} 会被 MyBatis-Plus
 * 自动转换成表名 {@code user}（默认开启驼峰转下划线），类的每个字段对应表中的一列。
 * 因此字段的命名、类型必须与数据库表保持一致，否则查询/插入时会报错或丢数据。</p>
 *
 * <p>为什么使用 Lombok 的 {@link Data}？
 * 因为它会在编译期自动生成 getter/setter/toString/equals/hashCode 等样板代码，
 * 让我们只关注业务字段本身，避免手写大量重复方法。</p>
 *
 * @author ming
 */
@Data
public class User {

    /**
     * 用户主键 ID。
     *
     * <p>对应表中 {@code id} 列，通常由数据库自增或雪花算法生成。
     * 使用包装类型 {@link Long} 而不是基本类型 {@code long}，
     * 是因为新增用户时 ID 还未生成，需要允许为 {@code null}。</p>
     */
    private Long id;

    /**
     * 登录用户名（账号），用于登录系统，必须唯一。
     */
    private String username;

    /**
     * 登录密码。
     *
     * <p>注意：数据库中存储的应当是加密后的密码（如 BCrypt 哈希），
     * 绝对不能明文存储，否则一旦数据库泄露会造成严重安全事故。
     * 该字段也不会通过 VO 返回给前端。</p>
     */
    private String password;

    /**
     * 用户昵称，用于在页面上展示，可以与用户名不同，允许重复。
     */
    private String nickname;

    /**
     * 手机号，可选字段，用于找回密码或接收通知。
     */
    private String phone;

    /**
     * 用户头像的 URL 地址（一般指向对象存储或静态资源服务器）。
     */
    private String avatar;

    /**
     * 账号状态。
     *
     * <ul>
     *     <li>{@code 1} —— 正常（active），可以正常登录使用</li>
     *     <li>{@code 0} —— 禁用（disabled），管理员封禁后无法登录</li>
     * </ul>
     */
    private Integer status;

    /**
     * 用户角色。
     *
     * <ul>
     *     <li>{@code 0} —— 普通用户（normal），只能进行买卖二手商品等基础操作</li>
     *     <li>{@code 1} —— 管理员（admin），可以管理用户、商品、分类等后台数据</li>
     * </ul>
     */
    private Integer role;

    /**
     * 账号创建时间。
     *
     * <p>使用 Java 8 引入的 {@link LocalDateTime} 而不是老旧的 {@link java.util.Date}，
     * 因为它是不可变对象、线程安全，并且 API 更直观（不会出现月份从 0 开始这种坑）。</p>
     */
    private LocalDateTime createTime;

    /**
     * 账号最近一次更新时间，每次修改用户信息时由数据库或代码刷新。
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志位。
     *
     * <p>{@link TableLogic} 是 MyBatis-Plus 提供的逻辑删除注解。
     * 加上它之后，调用 {@code removeById} 等方法不会真正执行 {@code DELETE} 语句，
     * 而是执行 {@code UPDATE user SET deleted = 1 WHERE id = ?}；
     * 同时所有查询都会自动追加 {@code WHERE deleted = 0} 条件，把已删除的数据过滤掉。</p>
     *
     * <p>为什么不直接物理删除？因为业务数据往往需要可追溯（例如订单关联的用户、
     * 审计日志等），物理删除会破坏外键关系并丢失历史，逻辑删除可以在保留数据的同时
     * 让业务层"看不见"它，必要时还能恢复。</p>
     *
     * <ul>
     *     <li>{@code 0} —— 未删除</li>
     *     <li>{@code 1} —— 已删除</li>
     * </ul>
     */
    @TableLogic
    private Integer deleted;
}
