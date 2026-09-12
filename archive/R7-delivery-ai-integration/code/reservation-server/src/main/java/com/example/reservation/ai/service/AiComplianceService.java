package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiComplianceVO;

/**
 * 预约合规校验服务（R7，POST /api/ai/compliance-check）
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
}
