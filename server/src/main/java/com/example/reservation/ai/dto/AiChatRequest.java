package com.example.reservation.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * 预约智能问答请求（POST /api/ai/chat，需求文档 2.5；R13-3 增加可选 history 多轮上下文）
 *
 * @author reservation-team
 */
@Data
public class AiChatRequest {

    /** 用户问题（场景绝对限定：仅解答预约/教室/个人记录相关问题） */
    private String question;

    /**
     * 会话内历史消息（可选，R13-3）：按时间正序，不含当前问题。
     * 缺省/空列表时退化为单轮问答，旧调用方零感知；后端不信任前端，统一二次裁剪。
     */
    private List<AiChatHistoryMessage> history;
}
