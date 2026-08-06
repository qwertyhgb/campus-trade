package com.ming.campustrade.service;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ming.campustrade.entity.OperationLog;
import com.ming.campustrade.mapper.OperationLogMapper;
import com.ming.campustrade.utils.UserHolder;
import com.ming.campustrade.vo.UserVO;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * 操作审计日志服务 —— 负责把关键操作写入 operation_log 表，以及供管理端分页查询。
 *
 * <p><b>为什么审计日志写库失败不能影响业务？</b><br>
 * 日志是“辅助能力”：业务（预约、审核、封禁）已经成功，只是留痕失败，
 * 不应该因此回滚业务或返回错误。所以 record 内部 try-catch 吞掉写库异常，
 * 降级为打一条 warn 日志 —— 与缓存失效、消息通知同级的“后置辅助”定位。</p>
 *
 * <p><b>敏感信息红线：</b>调用方传入的 detail 必须已经脱敏；
 * 密码、完整 Token、手机号、身份证号等敏感信息禁止进入本服务与日志表。</p>
 *
 * @author ming
 */
@Slf4j
@Service
@SuppressWarnings("null") // 抑制 MyBatis-Plus Lambda 方法引用（OperationLog::getCreateTime）与 Eclipse 空类型分析冲突的误报警告
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogService(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 记录一条操作日志（写库失败不影响业务，内部已降级）。
     *
     * <p>操作人默认取当前登录用户（UserHolder）；系统动作（无登录用户）可传 null。
     * IP 与 traceId 自动采集：IP 用 TCP 对端地址（不信任可伪造的 X-Forwarded-For），
     * traceId 从 MDC 取（由 TraceIdFilter 在请求进入时写入）。</p>
     *
     * @param action     操作动作编码（如 ACTIVITY_REVIEW / USER_BAN）
     * @param targetType 目标类型（activity/user/reservation/waitlist 等）
     * @param targetId   目标业务 ID
     * @param detail     操作详情（状态流转摘要，必须已脱敏）
     * @param success    操作结果：true=成功 false=失败
     * @param errorMsg   失败时的异常摘要（成功传 null）
     */
    public void record(String action, String targetType, Long targetId,
                       String detail, boolean success, String errorMsg) {
        recordInternal(resolveOperatorId(), action, targetType, targetId, detail, success, errorMsg);
    }

    /**
     * 记录一条系统自动操作日志。
     *
     * <p>定时任务、自动补位等动作不是某个用户直接点击产生的，因此 operatorId
     * 必须显式为 null，不能误记为恰好仍留在当前线程上下文里的用户。traceId/IP
     * 若不存在会自然为 null；这正是后台系统任务的正常表现。</p>
     *
     * @param action     操作动作编码
     * @param targetType 目标类型
     * @param targetId   目标业务 ID；批处理摘要可为 null
     * @param detail     已脱敏的操作摘要
     * @param success    操作结果
     * @param errorMsg   已脱敏的失败摘要
     */
    public void recordSystem(String action, String targetType, Long targetId,
                             String detail, boolean success, String errorMsg) {
        recordInternal(null, action, targetType, targetId, detail, success, errorMsg);
    }

    /**
     * 统一执行实际插入：普通请求传当前用户 ID，系统任务显式传 null。
     * 保持唯一写库入口，确保两类日志拥有相同的截断和降级策略。
     */
    private void recordInternal(Long operatorId, String action, String targetType, Long targetId,
                                String detail, boolean success, String errorMsg) {
        try {
            OperationLog operationLog = new OperationLog();
            operationLog.setOperatorId(operatorId);
            operationLog.setAction(action);
            operationLog.setTargetType(targetType);
            operationLog.setTargetId(targetId);
            operationLog.setDetail(truncate(detail, 500));
            operationLog.setSuccess(success ? 1 : 0);
            operationLog.setErrorMsg(truncate(errorMsg, 500));
            operationLog.setIp(resolveIp());
            operationLog.setTraceId(MDC.get("traceId"));
            operationLogMapper.insert(operationLog);
        } catch (Exception e) {
            // 审计留痕失败不影响业务：降级为日志，方便后续排查为什么没写进去
            log.warn("操作日志写入失败（不影响业务）：action={}, targetType={}, targetId={}",
                    action, targetType, targetId, e);
        }
    }

    /**
     * 管理端分页查询操作日志，按操作时间倒序。
     *
     * <p>供管理员后台“操作日志”页面使用；查询只读，不允许修改/删除历史记录。</p>
     *
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 操作日志分页对象
     */
    public IPage<OperationLog> page(int pageNo, int pageSize) {
        Page<OperationLog> page = new Page<>(pageNo, pageSize);
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        // createTime 精度是秒，同一秒可产生多条日志；再按自增 ID 倒序，
        // 才能让翻页顺序稳定，不会出现上一页末尾和下一页开头重复/遗漏。
        wrapper.orderByDesc(OperationLog::getCreateTime)
                .orderByDesc(OperationLog::getId);
        return operationLogMapper.selectPage(page, wrapper);
    }

    /**
     * 解析操作人 ID：优先当前登录用户，无登录用户（系统动作）返回 null。
     *
     * @return 操作人用户 ID；未登录返回 null
     */
    private Long resolveOperatorId() {
        UserVO currentUser = UserHolder.getUserVO();
        return (currentUser == null || currentUser.getId() == null) ? null : currentUser.getId();
    }

    /**
     * 解析客户端 IP：使用 TCP 对端地址（getRemoteAddr）。
     *
     * <p>不信任 X-Forwarded-For 请求头 —— 客户端可以伪造它；
     * 以后部署在受信任 Nginx 后面，再统一配置真实 IP 转发策略。</p>
     *
     * @return 客户端 IP；无法获取时返回 null
     */
    private String resolveIp() {
        HttpServletRequest request = RequestHolder.getRequest();
        return request == null ? null : request.getRemoteAddr();
    }

    /**
     * 截断超长文本，避免超过数据库字段长度导致插入失败。
     *
     * @param text   原始文本（可能为 null）
     * @param maxLen 最大长度
     * @return 截断后的文本；null 原样返回
     */
    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    /**
     * 请求上下文持有者：从 RequestContextHolder 取当前请求。
     *
     * <p>独立成静态方法是为了在非 Web 线程（定时任务、MQ 消费者）调用时
     * 安全返回 null，避免强转空指针。</p>
     */
    private static class RequestHolder {
        static HttpServletRequest getRequest() {
            org.springframework.web.context.request.RequestAttributes attrs =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
                return servletAttrs.getRequest();
            }
            return null;
        }
    }
}
