package com.ming.campustrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表留言数据传输对象（DTO，Data Transfer Object）。
 *
 * <p>用于接收用户发表商品留言或回复时提交的数据。使用 DTO 而非 {@code Comment} 实体接收，
 * 是因为 userId、createTime、deleted 等字段应由后端自动赋值，不应由前端传入。
 * DTO 只保留发表留言所需的字段，并附带校验规则。</p>
 *
 * <p>校验注解基于 Jakarta Bean Validation（{@code jakarta.*}，Spring Boot 3 / Java 21 标准），
 * 需配合 Controller 参数上的 {@code @Valid} 触发。</p>
 *
 * @author ming
 */
@Data
public class CommentAddDTO {

    /**
     * 商品 ID，必填。标识这条留言是留给哪件商品的。
     */
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    /**
     * 留言内容，必填，最长 500 个字符。
     */
    @NotBlank(message = "留言内容不能为空")
    @Size(max = 500, message = "留言内容不能超过500个字符")
    private String content;

    /**
     * 父留言 ID，选填。
     *
     * <ul>
     *     <li>{@code null} —— 发表顶级留言（直接对商品提问/评论）</li>
     *     <li>非 {@code null} —— 回复某条留言（值为被回复留言的 ID）</li>
     * </ul>
     */
    private Long parentId;

    /**
     * 被回复的用户 ID，选填。
     *
     * <p>回复留言时传入，用于前端展示 "回复 @xxx"。
     * 发表顶级留言时无需传入。</p>
     */
    private Long replyToUserId;
}
