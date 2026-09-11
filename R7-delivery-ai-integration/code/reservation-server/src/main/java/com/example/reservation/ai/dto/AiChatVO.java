package com.example.reservation.ai.dto;

import com.example.reservation.ai.config.AiConstants;
import lombok.Data;

/**
 * 预约智能问答结果 VO（POST /api/ai/chat）
 * 场景绝对限定：仅解答预约/教室/个人记录相关问题，无关问题返回预设话术
 *
 * @author reservation-team
 */
@Data
public class AiChatVO {

    /** AI 是否启用（false 时前端隐藏 AI 入口） */
    private boolean enabled;

    /** 提示信息（关闭时为友好提示） */
    private String message;

    /** 助手回答 */
    private String answer;

    /** AI 关闭时的友好返回 */
    public static AiChatVO disabled() {
        AiChatVO vo = new AiChatVO();
        vo.setEnabled(false);
        vo.setMessage(AiConstants.AI_DISABLED_MESSAGE);
        return vo;
    }
}
