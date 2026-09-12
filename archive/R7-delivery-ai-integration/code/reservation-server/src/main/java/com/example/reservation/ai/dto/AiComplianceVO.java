package com.example.reservation.ai.dto;

import com.example.reservation.ai.config.AiConstants;
import lombok.Data;

/**
 * 预约合规校验结果 VO（POST /api/ai/compliance-check）
 * 只提示不改状态：前端仅用于辅助管理员审核，审核动作仍由管理员手动执行
 *
 * @author reservation-team
 */
@Data
public class AiComplianceVO {

    /** AI 是否启用（false 时前端隐藏 AI 入口） */
    private boolean enabled;

    /** 提示信息（关闭时为友好提示） */
    private String message;

    /** 是否合规：true=合规，false=命中违规内容（红色高亮 + 悬浮原因） */
    private boolean compliant;

    /** 校验原因（不合规时说明违规原因） */
    private String reason;

    /** AI 关闭时的友好返回 */
    public static AiComplianceVO disabled() {
        AiComplianceVO vo = new AiComplianceVO();
        vo.setEnabled(false);
        vo.setMessage(AiConstants.AI_DISABLED_MESSAGE);
        return vo;
    }
}
