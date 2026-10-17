package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiComplianceVO;
import com.example.reservation.ai.support.StructuredStreamSink;

/**
 * 预约合规校验服务（R7，POST /api/ai/compliance-check；R13-5 新增 ?stream=true SSE）
 * 输入预约用途 → 合规结果（compliant/reason），辅助管理员审核；
 * 只提示不改状态：审核动作仍由管理员手动执行（需求文档 1.4 AI 业务规则 2）
 *
 * @author reservation-team
 */
public interface AiComplianceService {

    /**
     * 合规校验
     *
     * @param purpose 预约用途文本
     * @return 合规结果（ai.enable=false 时返回 enabled=false 的友好提示）
     */
    AiComplianceVO check(String purpose);

    /**
     * 流式合规入参校验（R13-5）：必须在 SSE 建立前（请求线程）执行，
     * 不合法抛 BusinessException，由全局异常处理器回 Result JSON。
     */
    void validateCheckInput(String purpose);

    /**
     * 流式合规校验（R13-5）：meta → phase（缓存未命中时）→ result（同构 VO）→ done。
     *
     * @param purpose 已通过 {@link #validateCheckInput} 的用途文本
     * @param userId  请求线程捕获的登录用户 ID（异步线程无 UserContext）
     * @param sink    结构化事件输出端口
     */
    void streamCheck(String purpose, Long userId, StructuredStreamSink<AiComplianceVO> sink);
}
