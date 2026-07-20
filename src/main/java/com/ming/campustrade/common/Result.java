package com.ming.campustrade.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 统一响应结果封装类
 *
 * 设计目的：
 * 前后端分离项目中，所有接口的返回值都使用统一的 JSON 结构，方便前端统一解析。
 * 无论成功还是失败，前端收到的 JSON 格式始终是：
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": { ... }   // 成功时有数据，失败时可能没有
 * }
 *
 * 为什么使用泛型 <T>？
 * 因为不同接口返回的数据类型不同（用户信息、商品列表、订单详情等），
 * 使用泛型可以让同一个 Result 类适配所有数据类型，避免为每种数据写一个响应类。
 *
 * @JsonInclude(JsonInclude.Include.NON_NULL) 的作用：
 * 当某个字段为 null 时，序列化 JSON 时直接跳过该字段，不会出现在响应体中。
 * 例如：error 响应中 data 为 null，那么返回的 JSON 里就不会有 "data": null 这一项，
 * 使响应体更简洁，也避免前端对 null 值做额外判断。
 *
 * @param <T> 响应数据的泛型类型
 */
@Data // Lombok 注解：自动生成 getter、setter、toString、equals、hashCode 方法，减少样板代码
@JsonInclude(JsonInclude.Include.NON_NULL) // Jackson 注解：序列化时忽略值为 null 的字段，使 JSON 响应更简洁
public class Result<T> {

    /** 业务状态码：200 表示成功，其他值表示各种错误（具体含义见 ResultCode 枚举） */
    private Integer code;

    /** 提示信息：成功时为 "success"，失败时为具体的错误描述（如 "用户名或密码错误"） */
    private String message;

    /** 响应数据：承载实际的业务数据（如用户对象、商品列表等），失败时通常为 null */
    private T data;

    /**
     * 私有构造函数 —— 静态工厂模式
     *
     * 为什么构造函数是 private 的？
     * 1. 禁止外部通过 new Result<>() 直接创建对象，强制调用者使用下面的静态工厂方法
     * 2. 静态工厂方法（success / error）语义更清晰，一眼就能看出是成功还是失败
     * 3. 可以在工厂方法内部统一设置 code 和 message，避免调用者忘记设置或设置错误
     * 4. 这是《Effective Java》推荐的"用静态工厂方法代替构造函数"的最佳实践
     */
    private Result() {}

    /**
     * 成功响应（无数据）
     *
     * 使用场景：操作成功但不需要返回数据，例如删除、修改操作
     * 示例：return Result.success();  →  {"code": 200, "message": "success"}
     *
     * @param <T> 泛型类型（此方法中 data 为 null，T 仅用于类型兼容）
     * @return 包含成功状态码的 Result 对象
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 成功响应（携带数据）
     *
     * 使用场景：查询操作成功后，将数据封装进 Result 返回给前端
     * 示例：return Result.success(userVO);  →  {"code": 200, "message": "success", "data": {...}}
     *
     * 流程：
     * 1. 创建一个空的 Result 对象
     * 2. 从 ResultCode.SUCCESS 枚举中取出成功状态码（200）和消息（"success"）
     * 3. 将业务数据设置到 data 字段
     * 4. 返回组装完成的 Result 对象
     *
     * @param data 要返回给前端的业务数据
     * @param <T>  业务数据的类型
     * @return 包含成功状态码和业务数据的 Result 对象
     */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());       // 设置状态码为 200
        result.setMessage(ResultCode.SUCCESS.getMessage()); // 设置消息为 "success"
        result.setData(data);                               // 设置业务数据
        return result;
    }

    /**
     * 失败响应（使用预定义的错误码枚举）
     *
     * 使用场景：业务逻辑校验失败，直接使用 ResultCode 中预定义的错误码和消息
     * 示例：return Result.error(ResultCode.USER_NOT_FOUND);  →  {"code": 1001, "message": "用户不存在"}
     *
     * @param resultCode 预定义的错误码枚举（包含 code 和 message）
     * @param <T>        泛型类型（error 响应通常不携带 data）
     * @return 包含错误状态码和错误消息的 Result 对象
     */
    public static <T> Result<T> error(ResultCode resultCode) {
        return error(resultCode.getCode(), resultCode.getMessage());
    }

    /**
     * 失败响应（使用预定义错误码 + 自定义消息）
     *
     * 使用场景：错误类型是已知的（如订单状态错误），但需要补充更具体的描述信息
     * 示例：return Result.error(ResultCode.ORDER_STATUS_ERROR, "订单已取消，无法确认");
     *
     * @param resultCode 预定义的错误码枚举（只取 code，message 用自定义的）
     * @param message    自定义的错误描述信息，覆盖枚举中的默认消息
     * @param <T>        泛型类型
     * @return 包含错误状态码和自定义消息的 Result 对象
     */
    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return error(resultCode.getCode(), message);
    }

    /**
     * 失败响应（完全自定义 code 和 message）
     *
     * 使用场景：需要灵活指定错误码和消息的底层方法，上面的 error 重载最终都会调用这个方法
     * 这是所有 error 方法的"最终执行者"，体现了方法复用的设计思想
     *
     * @param code    自定义错误码
     * @param message 自定义错误消息
     * @param <T>     泛型类型
     * @return 包含自定义错误码和消息的 Result 对象
     */
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);       // 设置错误状态码
        result.setMessage(message); // 设置错误描述信息
        // 注意：不设置 data，保持为 null，配合 @JsonInclude(NON_NULL) 序列化时不会出现 data 字段
        return result;
    }

    /**
     * 失败响应（仅传入错误消息，使用默认的 500 错误码）
     *
     * 使用场景：未预料到的异常情况，没有明确的业务错误码，统一归为服务器内部错误
     * 示例：return Result.error("系统繁忙，请稍后重试");  →  {"code": 500, "message": "系统繁忙，请稍后重试"}
     *
     * @param message 错误描述信息
     * @param <T>     泛型类型
     * @return 包含 500 错误码和自定义消息的 Result 对象
     */
    public static <T> Result<T> error(String message) {
        return error(ResultCode.INTERNAL_ERROR, message);
    }
}
