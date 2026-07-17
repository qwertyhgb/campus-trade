package com.ming.campustrade.common;

import lombok.Getter;

@Getter
public enum ResultCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不被允许"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // 业务相关（1001-1999 用户模块）
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_PASSWORD_ERROR(1002, "用户名或密码错误"),
    USER_ALREADY_EXISTS(1003, "用户已存在"),
    USER_ACCOUNT_DISABLED(1004, "账号已被禁用"),

    // 业务相关（2001-2999 商品模块）
    PRODUCT_NOT_FOUND(2001, "商品不存在"),
    PRODUCT_STATUS_ERROR(2002, "商品状态不合法"),

    // 业务相关（3001-3999 分类模块）
    CATEGORY_NOT_FOUND(3001, "分类不存在"),
    CATEGORY_ALREADY_EXISTS(3002, "分类名称已存在"),

    // 业务相关（4001-4999 订单模块）
    ORDER_NOT_FOUND(4001, "订单不存在"),
    ORDER_STATUS_ERROR(4002, "订单状态不合法"),
    PRODUCT_NOT_AVAILABLE(4003, "商品已售出或已下架"),
    CANNOT_BUY_OWN_PRODUCT(4004, "不能购买自己的商品");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
