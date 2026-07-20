package com.ming.campustrade.common.exception;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * 设计目的：
 * 集中处理 Controller 层抛出的所有异常，将异常转换为统一的 JSON 格式（Result 对象）返回给前端。
 * 这样 Controller 和 Service 中就不需要写大量的 try-catch，代码更简洁。
 *
 * @RestControllerAdvice 的工作原理：
 * 1. @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 * 2. Spring 启动时会扫描所有带此注解的类，注册为全局异常处理器
 * 3. 当 Controller 方法抛出异常时，Spring 的 DispatcherServlet 会拦截异常
 * 4. 然后按照"最精确匹配"原则，找到对应的 @ExceptionHandler 方法来处理
 * 5. 处理方法的返回值会自动序列化为 JSON 写入 HTTP 响应体（因为 @ResponseBody）
 *
 * 异常处理的优先级（从精确到宽泛）：
 * 1. BusinessException        → 业务异常（Service 层主动抛出的可预期错误）
 * 2. MethodArgumentNotValidException → @Valid 校验 @RequestBody 参数失败
 * 3. ConstraintViolationException    → @Validated 校验 @PathVariable/@RequestParam 参数失败
 * 4. BindException            → 表单参数绑定失败
 * 5. MissingServletRequestParameterException → 缺少必填的请求参数
 * 6. MethodArgumentTypeMismatchException     → 参数类型转换失败
 * 7. HttpRequestMethodNotSupportedException  → 请求方法不对（如 GET 访问 POST 接口）
 * 8. Exception                → 兜底处理器，捕获所有未被上面处理的异常
 *
 * 为什么 BusinessException 返回 HTTP 200 + body 中的错误码？
 * 这是国内项目（尤其是前后端分离项目）的常见做法：
 * 1. HTTP 状态码始终返回 200，真正的业务状态码放在 JSON body 的 code 字段中
 * 2. 前端统一拦截响应，根据 code 字段判断成功/失败：code === 200 为成功，否则为失败
 * 3. 好处：前端不需要同时处理 HTTP 错误和业务错误两套逻辑，统一在响应拦截器中处理
 * 4. 如果返回 HTTP 4xx/5xx，axios 等库会直接进入 catch 分支，增加前端处理复杂度
 *
 * 注意：只有"请求方法不被允许"返回了 HTTP 405 状态码（使用 ResponseEntity），
 * 因为这属于协议层面的错误，不是业务错误，应该让 HTTP 层面也能感知到。
 */
@Slf4j // Lombok 注解：自动生成 log 对象，用于记录日志（log.info / log.warn / log.error）
@RestControllerAdvice // 标记为全局异常处理器，Spring 会自动将 Controller 抛出的异常路由到此类
public class GlobalExceptionHandler {

    /**
     * 处理器 1：业务异常
     *
     * 触发时机：Service 层通过 throw new BusinessException(...) 主动抛出的异常
     * 处理策略：
     * 1. 记录 warn 级别日志（业务异常是"可预期的"，不算系统错误，所以用 warn 而非 error）
     * 2. 将异常中的 code 和 message 原样封装为 Result 返回给前端
     * 3. HTTP 状态码保持 200，前端通过 body 中的 code 字段判断失败
     *
     * 示例：Service 中 throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND)
     * 前端收到：{"code": 2001, "message": "商品不存在"}
     *
     * @param e 业务异常对象，包含错误码和错误消息
     * @return 统一格式的失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        // 使用 warn 级别：业务异常是正常流程的一部分（如用户输入了错误的密码），不需要告警
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理器 2：@Valid 参数校验失败（针对 @RequestBody 注解的 JSON 参数）
     *
     * 触发时机：Controller 方法参数上标注了 @Valid，且请求体中的 JSON 数据不满足校验规则
     * 例如：@PostMapping + @Valid @RequestBody UserLoginDTO dto，当 dto 中的 username 为空时触发
     *
     * 处理策略：
     * 1. 从 BindingResult 中提取所有字段的校验错误信息
     * 2. 用中文分号"；"将多条错误信息拼接成一个字符串
     * 3. 返回 400 错误码 + 拼接后的错误消息
     *
     * 示例：前端提交 {"username": "", "password": "123"}
     * 前端收到：{"code": 400, "message": "用户名不能为空"}
     *
     * @param e Spring MVC 抛出的参数校验异常
     * @return 统一格式的失败响应，包含所有校验错误信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        // 从异常中提取所有字段错误，将每个错误的 defaultMessage 拼接起来
        // 使用 Java 21 的 Stream API 进行函数式处理
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "参数错误")
                .collect(Collectors.joining("；")); // 多条错误用中文分号分隔
        log.warn("参数校验失败：{}", msg);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理器 3：@Validated 约束校验失败（针对 @PathVariable / @RequestParam 注解的参数）
     *
     * 触发时机：Controller 类上标注了 @Validated，方法参数上使用了约束注解（如 @Min、@Max、@NotBlank）
     * 例如：@GetMapping("/product/{id}") + @PathVariable @Min(1) Long id，当 id=0 时触发
     *
     * 与处理器 2 的区别：
     * - MethodArgumentNotValidException：校验 @RequestBody 中的 JSON 对象字段
     * - ConstraintViolationException：校验 URL 路径参数或查询参数上的约束注解
     *
     * @param e Jakarta Validation 抛出的约束违反异常
     * @return 统一格式的失败响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        // 提取所有约束违反的消息并拼接
        String msg = e.getConstraintViolations().stream()
                .map(violation -> violation.getMessage() != null ? violation.getMessage() : "约束校验失败")
                .collect(Collectors.joining("；"));
        log.warn("约束校验失败：{}", msg);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理器 4：表单参数绑定异常
     *
     * 触发时机：Controller 方法参数是一个对象（没有 @RequestBody），Spring 尝试将表单参数绑定到对象字段时失败
     * 例如：GET 请求的查询参数绑定到 DTO 对象时，字段校验不通过
     *
     * 与处理器 2 的区别：
     * - MethodArgumentNotValidException：处理 @RequestBody（JSON 请求体）的校验
     * - BindException：处理表单提交（form-data / 查询参数）的绑定校验
     *
     * @param e Spring MVC 抛出的绑定异常
     * @return 统一格式的失败响应
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "参数错误")
                .collect(Collectors.joining("；"));
        log.warn("表单绑定异常：{}", msg);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理器 5：缺少必填的请求参数
     *
     * 触发时机：Controller 方法参数标注了 @RequestParam(required=true)（默认就是 required=true），
     * 但前端请求中没有传递该参数
     * 例如：接口需要 ?pageNo=1&pageSize=10，但前端只传了 ?pageNo=1，缺少 pageSize
     *
     * @param e Spring MVC 抛出的缺少参数异常
     * @return 统一格式的失败响应，提示缺少哪个参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        // String.format 拼接缺失的参数名，给用户明确的提示
        String msg = String.format("缺少必填参数：%s", e.getParameterName());
        log.warn(msg);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理器 6：参数类型不匹配
     *
     * 触发时机：前端传递的参数值无法转换为 Controller 方法参数声明的类型
     * 例如：接口声明 @PathVariable Long id，但前端传了 /product/abc（abc 无法转为 Long）
     *
     * @param e Spring MVC 抛出的类型转换异常
     * @return 统一格式的失败响应，提示哪个参数类型不对
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = String.format("参数类型不正确：%s", e.getName());
        log.warn(msg);
        return Result.error(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理器 7：请求方法不被允许
     *
     * 触发时机：前端使用了接口不支持的 HTTP 方法
     * 例如：接口只支持 POST，但前端发了 GET 请求
     *
     * 特殊处理：
     * 这是所有处理器中唯一返回 ResponseEntity 的，因为它需要设置 HTTP 状态码为 405。
     * 其他业务异常都返回 HTTP 200 + body 中的错误码，但"方法不允许"属于协议层错误，
     * 应该在 HTTP 层面就体现出来（返回 405 状态码），方便浏览器和中间件识别。
     *
     * @param e Spring MVC 抛出的方法不支持异常
     * @return 包含 HTTP 405 状态码的 ResponseEntity
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不被允许：{}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED) // 设置 HTTP 状态码为 405
                .body(Result.error(ResultCode.METHOD_NOT_ALLOWED.getCode(), ResultCode.METHOD_NOT_ALLOWED.getMessage()));
    }

    /**
     * 处理器 8：兜底异常处理器（捕获所有未被上面处理器匹配的异常）
     *
     * 触发时机：任何未被前面 7 个处理器捕获的异常都会最终到达这里
     * 例如：NullPointerException、数据库连接超时、第三方 API 调用失败等未预期的系统错误
     *
     * 处理策略：
     * 1. 使用 error 级别记录完整的异常堆栈（方便开发人员排查 Bug）
     * 2. 返回通用的"服务器内部错误"提示（不暴露具体的异常信息给前端，防止安全漏洞）
     *
     * 为什么不把 e.getMessage() 返回给前端？
     * 因为系统异常的 message 可能包含敏感信息（如 SQL 语句、服务器路径、类名等），
     * 暴露给前端可能被恶意利用。对用户只需展示"服务器内部错误"即可。
     *
     * @param e 未被处理的异常
     * @return 统一格式的失败响应（500 服务器内部错误）
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 使用 error 级别 + 完整堆栈：系统异常是 Bug，需要开发人员关注并修复
        log.error("系统异常", e);
        return Result.error(ResultCode.INTERNAL_ERROR.getCode(), ResultCode.INTERNAL_ERROR.getMessage());
    }
}
