package com.ming.campustrade.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色权限注解 —— 标注在 Controller 方法或类上，表示访问该接口需要的最低角色等级
 *
 * 角色等级说明：
 * - 0 = 普通用户（默认值，只要登录了就能访问）
 * - 1 = 管理员（需要管理员权限才能访问）
 *
 * 使用示例：
 * <pre>
 * // 示例 1：标注在方法上 —— 只有管理员才能删除商品
 * {@code @RequireRole(1)}
 * {@code @DeleteMapping("/product/{id}")}
 * public Result<Void> deleteProduct(@PathVariable Long id) { ... }
 *
 * // 示例 2：标注在类上 —— 该 Controller 下所有接口都需要管理员权限
 * {@code @RequireRole(1)}
 * {@code @RestController}
 * {@code @RequestMapping("/admin")}
 * public class AdminController { ... }
 *
 * // 示例 3：不标注或 @RequireRole(0) —— 普通登录用户即可访问（默认行为）
 * {@code @GetMapping("/product/list")}
 * public Result<List<ProductVO>> list() { ... }
 * </pre>
 *
 * 工作原理（由 RoleInterceptor 拦截器处理）：
 * 1. 用户请求到达 Controller 之前，Spring MVC 的拦截器链会先执行 RoleInterceptor
 * 2. RoleInterceptor 通过反射检查目标 Controller 方法或类上是否有 @RequireRole 注解
 * 3. 如果有，取出注解的 value 值（要求的最低角色等级）
 * 4. 从当前登录用户信息中获取用户的实际角色等级（UserHolder.getUserVO().getRole()）
 * 5. 比较：如果用户角色 >= 注解要求的角色，放行；否则返回 403 无权限错误
 * 6. 如果方法上没有注解，则检查类上是否有；都没有则默认放行（等同于 @RequireRole(0)）
 *
 * 注解元注解说明：
 * - @Target：指定注解可以标注在哪些位置
 * - @Retention：指定注解的生命周期（保留到什么时候）
 */
@Target({ElementType.METHOD, ElementType.TYPE}) // 可以标注在方法（METHOD）和类（TYPE）上
@Retention(RetentionPolicy.RUNTIME) // 保留到运行时，这样 RoleInterceptor 才能通过反射读取到该注解
public @interface RequireRole {

    /**
     * 访问该接口所需的最低角色等级
     *
     * 默认值为 0（普通用户），即只要登录了就能访问。
     * 设置为 1 表示需要管理员权限。
     *
     * 为什么用 int 而不是枚举？
     * 因为角色等级本质上是数值比较（用户角色 >= 要求角色），用 int 更直观，
     * 且方便未来扩展更多角色等级（如 2=超级管理员）而不需要修改注解定义。
     *
     * @return 最低角色等级，0=普通用户，1=管理员
     */
    int value() default 0;
}
