package com.ming.campustrade.aspect;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import com.ming.campustrade.annotation.OperationLog;
import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.exception.BusinessException;
import com.ming.campustrade.service.OperationLogService;

import lombok.extern.slf4j.Slf4j;

/**
 * 操作日志切面 —— 拦截标注了 {@link OperationLog} 的 Controller 方法，自动写入审计日志。
 *
 * <p><b>切面做了什么？</b>（环绕通知，方法成功与失败都会记录）</p>
 * <ol>
 *   <li>从注解取动作编码、目标类型、目标 ID 参数名、详情描述；</li>
 *   <li>按参数名从方法实参中提取目标业务 ID（如 activityId / userId）；</li>
 *   <li>执行原方法（proceed）；</li>
 *   <li>成功后记录“成功”日志；异常后记录“失败”日志并原样抛出异常
 *       （审计记录失败信息，但绝不影响业务异常的正常传播）；</li>
 *   <li>操作人、IP、traceId 由 OperationLogService 内部自动采集。</li>
 * </ol>
 *
 * <p><b>为什么切面不记录完整方法参数？</b><br>
 * 完整参数可能包含密码、Token、手机号等敏感信息 —— 审计日志只记录
 * “动作 + 目标 ID + 静态描述 + 结果”，从源头杜绝敏感信息进入日志表。</p>
 *
 * <p><b>为什么记录日志放在 proceed() 之后而不是之前？</b><br>
 * 先执行业务再留痕：只有业务真正执行（无论成功失败）才值得记录；
 * 并且日志写在 Controller 层环绕，业务事务已在 Service 内提交，
 * 日志写库失败也不会回滚业务（OperationLogService 内部还会再降级一次）。</p>
 *
 * @author ming
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    private final OperationLogService operationLogService;

    /** 参数名发现器：读取方法参数的编译期名称（依赖 -parameters，Spring Boot 默认开启）。 */
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    public OperationLogAspect(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /**
     * 环绕通知：方法执行前后分别记录审计日志（成功/失败都会留痕）。
     *
     * @param joinPoint     被拦截方法的连接点（含方法签名与实参）
     * @param operationLog  方法上标注的审计注解（动作/目标/描述）
     * @return 原方法的返回值（原样透传，不影响业务）
     * @throws Throwable 原方法异常原样抛出（审计只记录，不吞异常）
     */
    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        Long targetId = extractTargetId(joinPoint, operationLog.targetIdParam());
        try {
            Object result = joinPoint.proceed();

            // “创建”活动这类接口在请求进入时尚无 ID，只能从成功响应的 Result.data
            // 中取得数据库刚生成的 ID。其他接口仍优先使用 URL/DTO 里的目标 ID。
            if (targetId == null && operationLog.targetIdFromResult()) {
                targetId = extractTargetIdFromResult(result);
            }
            // 成功留痕
            operationLogService.record(
                    operationLog.action(),
                    operationLog.targetType(),
                    targetId,
                    operationLog.description(),
                    true,
                    null);
            return result;
        } catch (Throwable e) {
            // 失败留痕：记录异常摘要（只取消息不取堆栈，避免日志表过大）
            operationLogService.record(
                    operationLog.action(),
                    operationLog.targetType(),
                    targetId,
                    operationLog.description(),
                    false,
                    safeErrorMsg(e));
            // 审计只记录，不吞异常：原样抛出，让全局异常处理正常返回给前端
            throw e;
        }
    }

    /**
     * 按注解指定的参数路径从方法实参中提取目标业务 ID。
     *
     * <p>支持两种写法：</p>
     * <ul>
     *   <li>"activityId"：目标 ID 直接是某个方法参数（路径变量）；</li>
     *   <li>"dto.id"：目标 ID 藏在 DTO 参数里（如审核接口的
     *       ActivityReviewDTO.getId()），用点号路径 + getter 反射读取。</li>
     * </ul>
     *
     * @param joinPoint     被拦截方法连接点
     * @param targetIdParam 注解指定的参数路径（如 "activityId" 或 "dto.id"）
     * @return 目标业务 ID；提取失败时返回 null（日志仍会记录，只是目标为空）
     */
    private Long extractTargetId(ProceedingJoinPoint joinPoint, String targetIdParam) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(signature.getMethod());
        Object[] args = joinPoint.getArgs();
        if (parameterNames == null || targetIdParam == null) {
            return null;
        }

        // 拆分点号路径：参数名 + 可选属性名（如 "dto.id" → 参数 dto 的 id 属性）
        String[] parts = targetIdParam.split("\\.");
        String paramName = parts[0];
        String property = parts.length > 1 ? parts[1] : null;

        // 1. 找到匹配参数名的实参对象
        Object target = null;
        for (int i = 0; i < parameterNames.length; i++) {
            if (paramName.equals(parameterNames[i])) {
                target = args[i];
                break;
            }
        }
        if (target == null) {
            return null;
        }

        // 2. 无属性名：实参本身就是 ID；有属性名：反射调用 getter 取出 ID
        Object idValue = (property == null) ? target : invokeGetter(target, property);
        return idValue instanceof Long id ? id : null;
    }

    /**
     * 反射调用 getter（如属性 id → getId()）。
     *
     * <p>只用于审计日志提取目标 ID，不涉及任何业务逻辑；
     * 反射失败（无 getter/方法不可访问）时返回 null，不影响主流程。</p>
     *
     * @param target   属性所在对象
     * @param property 属性名（如 "id"）
     * @return 属性值；反射失败返回 null
     */
    private Object invokeGetter(Object target, String property) {
        try {
            String getterName = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            Method getter = target.getClass().getMethod(getterName);
            return getter.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从统一成功响应 {@link Result} 的 data 字段中提取目标 ID。
     *
     * <p>这里仅接受 {@link Number}，而不是反射整个返回对象：审计切面只需要
     * “创建后生成的主键”，不应该因为记录日志而读取或序列化完整业务数据。</p>
     *
     * @param result Controller 原始返回值
     * @return data 是数值型 ID 时返回其 Long 值；其余情况返回 null
     */
    private Long extractTargetIdFromResult(Object result) {
        if (result instanceof Result<?> apiResult && apiResult.getData() instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /**
     * 生成可安全入库的异常摘要。
     *
     * <p>不能直接保存 {@code e.getMessage()}：底层异常消息可能带 SQL、请求参数，
     * 甚至包含用户提交的敏感内容。业务异常只记录稳定的错误码；未知异常只记录
     * 异常类型。需要完整堆栈时仍应到带 traceId 的应用日志中排查。</p>
     *
     * @param e 业务异常
     * @return 不含请求原文的受控异常摘要
     */
    private String safeErrorMsg(Throwable e) {
        if (e instanceof BusinessException businessException) {
            return "业务异常，错误码=" + businessException.getCode();
        }
        return "系统异常：" + e.getClass().getSimpleName();
    }
}
