package com.ming.campustrade.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公开接口注解（免登录访问）
 *
 * 标注在 Controller 的方法或类上，表示该接口不需要用户登录即可访问。
 * LoginInterceptor 在拦截请求时会检查此注解：
 * - 标注在方法上：该方法对应的接口公开
 * - 标注在类上：该 Controller 下所有接口都公开
 *
 * 使用示例：
 * <pre>{@code
 * // 方法级别：只有商品详情接口公开
 * @PublicApi
 * @GetMapping("/{id}")
 * public Result<ProductVO> getById(@PathVariable Long id) { ... }
 *
 * // 类级别：整个 Controller 的所有接口都公开
 * @PublicApi
 * @RestController
 * @RequestMapping("/public")
 * public class PublicController { ... }
 * }</pre>
 *
 * 注意：@Target 必须同时包含 METHOD 和 TYPE，
 * 否则标注在类上时编译器会报错（之前只有 METHOD 导致类级别使用不生效）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {
}
