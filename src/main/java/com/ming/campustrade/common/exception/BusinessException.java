package com.ming.campustrade.common.exception;

import com.ming.campustrade.common.ResultCode;
import lombok.Getter;

/**
 * 自定义业务异常类
 *
 * 设计目的：
 * 在业务逻辑中，当出现"可预期的业务错误"时（如用户不存在、商品已售出、订单状态不对等），
 * 抛出此异常来中断当前流程，由全局异常处理器（GlobalExceptionHandler）统一捕获并转换为友好的 JSON 响应。
 *
 * 什么时候该抛出 BusinessException？
 * - 业务规则校验失败时（如：不能购买自己的商品、订单状态不允许取消）
 * - 数据不存在时（如：根据 ID 查不到商品、用户、订单）
 * - 权限不足时（如：非卖家本人尝试确认订单）
 *
 * 什么时候不该用 BusinessException？
 * - 系统级错误（如数据库连接失败、空指针）：这些属于未预期的 Bug，应让兜底异常处理器捕获
 * - 参数格式错误（如手机号格式不对）：这些由 @Valid 注解自动校验，不需要手动抛异常
 *
 * 工作流程：
 * 1. Service 层检测到业务规则被违反
 * 2. 抛出 new BusinessException(ResultCode.PRODUCT_NOT_FOUND) 或 new BusinessException(ResultCode.PRODUCT_NOT_FOUND, "自定义消息")
 * 3. 异常向上传播，被 GlobalExceptionHandler 中的 @ExceptionHandler(BusinessException.class) 捕获
 * 4. 处理器将异常中的 code 和 message 封装为 Result 对象返回给前端
 * 5. 前端根据 code 判断失败，展示 message 给用户
 *
 * 为什么继承 RuntimeException 而不是 Exception？
 * 1. RuntimeException 是非受检异常（Unchecked Exception），抛出时不强制要求调用者 try-catch
 * 2. 这样 Service 层抛异常后，Controller 层不需要写 try-catch，代码更简洁
 * 3. 异常会一直向上传播，直到被 GlobalExceptionHandler 统一处理
 */
@Getter // Lombok 注解：自动生成 getCode() 方法，让 GlobalExceptionHandler 能获取错误码
public class BusinessException extends RuntimeException {

    /**
     * 业务错误码
     * 对应 ResultCode 枚举中的 code 值，前端根据此码判断具体的错误类型
     * 使用 final 修饰：错误码一旦确定就不应被修改，保证异常对象的不可变性
     */
    private final Integer code;

    /**
     * 构造函数 1：仅传入错误消息（使用默认的 500 错误码）
     *
     * 使用场景：简单的业务错误，不需要特定的错误码，统一用 500
     * 示例：throw new BusinessException("操作失败，请稍后重试");
     *
     * @param message 错误描述信息，会直接展示给用户
     */
    public BusinessException(String message) {
        super(message); // 调用父类 RuntimeException 的构造函数，设置异常消息
        this.code = ResultCode.INTERNAL_ERROR.getCode(); // 默认使用 500 错误码
    }

    /**
     * 构造函数 2：传入错误消息 + 自定义错误码
     *
     * 使用场景：需要指定特定错误码，但消息是动态生成的
     * 示例：throw new BusinessException("库存不足，剩余 " + stock + " 件", 2003);
     *
     * @param message 错误描述信息
     * @param code    自定义的业务错误码
     */
    public BusinessException(String message, Integer code) {
        super(message);
        this.code = code;
    }

    /**
     * 构造函数 3：传入 ResultCode 枚举（最常用）
     *
     * 使用场景：错误码和消息都已在 ResultCode 枚举中预定义好，直接使用
     * 示例：throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
     * 效果：code=2001, message="商品不存在"
     *
     * 这是最推荐的用法，因为：
     * 1. 错误码和消息集中管理，不会写错
     * 2. 代码简洁，一个枚举值搞定
     * 3. 修改提示信息只需改 ResultCode 枚举一处
     *
     * @param resultCode 预定义的错误码枚举
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage()); // 使用枚举中预定义的消息
        this.code = resultCode.getCode(); // 使用枚举中预定义的错误码
    }

    /**
     * 构造函数 4：传入 ResultCode 枚举 + 自定义消息
     *
     * 使用场景：错误码用枚举中预定义的，但消息需要更具体的描述
     * 示例：throw new BusinessException(ResultCode.ORDER_STATUS_ERROR, "订单已取消，无法确认");
     * 效果：code=4002（来自枚举），message="订单已取消，无法确认"（自定义）
     *
     * 为什么不直接用构造函数 3？
     * 因为同一个错误码可能对应不同的具体场景，需要给用户更精确的提示。
     * 比如 ORDER_STATUS_ERROR 可能是"不允许确认"也可能是"不允许取消"，
     * 用自定义消息可以区分这些场景。
     *
     * @param resultCode 预定义的错误码枚举（只取 code）
     * @param message    自定义的错误描述信息（覆盖枚举中的默认消息）
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message); // 使用自定义消息
        this.code = resultCode.getCode(); // 使用枚举中的错误码
    }
}
