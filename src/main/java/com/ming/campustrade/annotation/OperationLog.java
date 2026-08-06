package com.ming.campustrade.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解 —— 标注在需要审计留痕的 Controller 方法上。
 *
 * <p>配合 {@code OperationLogAspect} 使用：被标注的方法执行成功后，
 * 切面自动把“谁、何时、对什么、做了什么、结果如何”写入 operation_log 表；
 * 业务方法自身不需要写任何日志代码 —— 审计能力通过 AOP 横切注入。</p>
 *
 * <p><b>为什么不标注在 Service 方法上？</b><br>
 * Controller 是 HTTP 入口，天然拥有“当前用户、当前请求”上下文；
 * Service 可能被定时任务、MQ 消费者或其他 Service 调用，
 * 在 Service 上标注会把内部调用也记成用户操作，语义不准确。</p>
 *
 * @author ming
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /**
     * 操作动作编码（写入 operation_log.action）。
     *
     * <p>建议风格：模块_动作，如 ACTIVITY_REVIEW（审核活动）、USER_BAN（封禁用户）、
     * RESERVATION_CREATE（预约活动）。编码集中管理，方便管理端筛选统计。</p>
     *
     * @return 动作编码
     */
    String action();

    /**
     * 目标类型（写入 operation_log.target_type）。
     *
     * <p>如 activity / user / reservation / waitlist。</p>
     *
     * @return 目标类型
     */
    String targetType();

    /**
     * 目标业务 ID 对应的方法参数名。
     *
     * <p>切面会按参数名从方法参数列表中提取目标 ID（如 id / activityId / userId）；
     * 参数名依赖编译期 -parameters（Spring Boot 默认开启）。</p>
     *
     * @return 参数名，默认 "id"
     */
    String targetIdParam() default "id";

    /**
     * 目标 ID 是否从成功响应的 {@code Result.data} 中读取。
     *
     * <p>适用于“创建”接口：创建前还没有业务 ID，因此无法从请求参数中提取；
     * Controller 成功后通常会返回 {@code Result<Long>}，切面便可从其中取出
     * 数据库生成的新 ID。普通编辑、删除接口仍应使用 {@link #targetIdParam()}。</p>
     *
     * @return true 表示从成功响应的 data 字段提取数值型目标 ID
     */
    boolean targetIdFromResult() default false;

    /**
     * 操作详情（写入 operation_log.detail）。
     *
     * <p>静态描述即可，如“审核活动”、“下架活动”；状态流转细节由各接口
     * 业务日志补充。禁止在 detail 中拼入密码、Token 等敏感信息。</p>
     *
     * @return 操作详情描述
     */
    String description() default "";
}
