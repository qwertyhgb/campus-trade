package com.ming.campustrade.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 操作审计日志实体类，对应数据库中的 {@code operation_log} 表。
 *
 * <p><b>【本表的作用】</b><br>
 * 记录每一次关键写操作：谁（operatorId）、在什么时候（createTime）、
 * 对什么（targetType + targetId）、做了什么（action + detail）、结果如何（success）。
 * 管理员可以在后台按操作人或操作对象检索，实现“操作可追溯、责任可定位”。</p>
 *
 * <p><b>【谁写入这张表？】</b><br>
 * 不是业务 Service 直接写，而是 OperationLogAspect（AOP 切面）拦截标注了
 * {@code @OperationLog} 注解的 Controller 方法，在方法成功/失败后自动写入 ——
 * 业务代码不需要感知日志逻辑，日志代码也不侵入业务。</p>
 *
 * <p><b>【为什么没有 deleted 字段？】</b><br>
 * 审计日志是“只能追加、不能删除”的证据链：即使管理员操作也要留痕，
 * 逻辑删除会破坏审计完整性（表设计如此，不要添加不存在的字段）。</p>
 *
 * <p><b>【敏感信息红线】</b><br>
 * detail 字段必须脱敏后写入：密码、完整 Token、手机号、身份证号等
 * 敏感信息一律不允许出现在日志表和日志文件中（见 OperationLogAspect）。</p>
 *
 * @author ming
 */
@Data
@TableName("operation_log")
public class OperationLog {

    /** 主键 ID，由数据库自增生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作人用户 ID（未登录的系统动作如定时任务可为 null）。 */
    private Long operatorId;

    /** 操作动作编码，如 ACTIVITY_REVIEW / USER_BAN / RESERVATION_CREATE。 */
    private String action;

    /** 目标类型（activity/user/reservation/waitlist 等）。 */
    private String targetType;

    /** 目标业务 ID。 */
    private Long targetId;

    /** 操作详情（状态流转等业务摘要，必须已脱敏）。 */
    private String detail;

    /** 操作结果：1成功 0失败。 */
    private Integer success;

    /** 失败时的异常摘要（成功时为 null）。 */
    private String errorMsg;

    /** 操作来源 IP（TCP 对端地址，不信任可伪造的 X-Forwarded-For）。 */
    private String ip;

    /** 请求追踪 ID（与日志文件 MDC 中的 traceId 对应）。 */
    private String traceId;

    /** 操作时间（数据库默认 CURRENT_TIMESTAMP）。 */
    private LocalDateTime createTime;
}
