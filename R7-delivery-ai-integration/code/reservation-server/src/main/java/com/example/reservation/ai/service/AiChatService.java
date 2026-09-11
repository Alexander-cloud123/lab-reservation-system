package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiChatVO;

/**
 * 预约智能问答服务（R7，POST /api/ai/chat）
 * 场景绝对限定（需求文档 1.4 AI 业务规则 1）：仅解答预约/教室/个人记录相关问题，
 * 无关问题统一返回预设话术；个人数据通过检索增强注入上下文
 *
 * @author reservation-team
 */
public interface AiChatService {

    /**
     * 场景限定问答
     *
     * @param question 用户问题
     * @return 助手回答（无关问题返回预设话术；ai.enable=false 时返回 enabled=false 的友好提示）
     */
    AiChatVO chat(String question);
}
