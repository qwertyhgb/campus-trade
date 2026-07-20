package com.ming.campustrade.vo;

import lombok.Data;

/**
 * 登录结果视图对象（VO，View Object），用于登录成功后向前端返回数据。
 *
 * <p>登录接口需要同时返回两样东西：用于后续身份认证的 {@code token}，
 * 以及当前登录用户的基本信息 {@link UserVO}（注意这里复用了 UserVO，
 * 而不是返回完整的 User 实体，从而避免暴露密码等敏感字段）。
 * 把这两部分封装到一个 VO 里，可以让接口返回结构更清晰，前端一次请求即可拿到全部所需数据。</p>
 *
 * @author ming
 */
@Data
public class LoginVO {

    /**
     * 身份认证令牌（Token）。
     *
     * <p>登录成功后由后端生成，前端需保存下来，并在之后每次请求时携带它，
     * 后端据此识别"当前请求是哪个用户发出的"，实现无状态的身份认证。</p>
     */
    private String token;

    /** 当前登录用户的基本信息（已过滤密码等敏感字段）。 */
    private UserVO userVO;
}
