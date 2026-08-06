package com.ming.campustrade.common.constant;

import java.util.HashMap;
import java.util.Map;

/**
 * 幂等场景枚举 —— 限定哪些写操作可以使用幂等 Token。
 *
 * <p><b>为什么用枚举而不是让前端随意传字符串？</b><br>
 * 如果直接接受任意字符串（如 scene=anything），Token 的 Key 会五花八门：
 * ① 拼出来的 Key 不可控，容易与其他业务 Key 冲突；<br>
 * ② 前端拼错场景名会导致 Token 领取和消费对不上，白白增加排查成本。<br>
 * 用枚举把场景限定为三个固定值，领取和消费都引用同一个枚举，天然一致。</p>
 *
 * <p><b>场景值为什么用 "activity:create" 这种冒号分段格式？</b><br>
 * 场景值会拼进 Redis Key（idempotency:token:{userId}:{scene}:{token}），
 * 冒号分段与 Redis Key 的命名惯例一致，可读性好、方便排查。</p>
 *
 * @author ming
 */
public enum IdempotencyScene {

    /** 创建活动（POST /activity/create） */
    ACTIVITY_CREATE("activity:create"),

    /** 预约活动（POST /reservation/reserve 等预约接口） */
    ACTIVITY_RESERVE("activity:reserve"),

    /** 加入候补（POST /waitlist/join 等候补接口） */
    ACTIVITY_WAITLIST("activity:waitlist");

    /** 场景的固定字符串值（直接拼进 Redis Key） */
    private final String value;

    /**
     * 枚举构造器（Java 枚举的构造函数只能是 private 的）。
     *
     * @param value 场景的固定字符串值
     */
    IdempotencyScene(String value) {
        this.value = value;
    }

    /**
     * 获取场景的固定字符串值。
     *
     * @return 场景值，如 "activity:create"
     */
    public String getValue() {
        return value;
    }

    /**
     * 把前端传来的场景字符串转换为枚举。
     *
     * <p>前端只允许传枚举中定义的三个固定值；传了其他任何字符串都返回 null，
     * 由调用方（Controller）抛“幂等场景不合法”业务异常 ——
     * 从入口就拦截掉任意场景字符串。</p>
     *
     * @param value 前端传来的场景字符串
     * @return 匹配的枚举；不合法返回 null
     */
    public static IdempotencyScene fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return VALUE_MAP.get(value);
    }

    /**
     * 场景值 → 枚举 的查找表（类加载时构建一次，避免每次 fromValue 都遍历数组）。
     */
    private static final Map<String, IdempotencyScene> VALUE_MAP = new HashMap<>();

    static {
        for (IdempotencyScene scene : values()) {
            VALUE_MAP.put(scene.value, scene);
        }
    }
}
