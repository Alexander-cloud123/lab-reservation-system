package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 预约智能问答请求（POST /api/ai/chat，需求文档 2.5）
 *
 * @author reservation-team
 */
@Data
public class AiChatRequest {

    /** 用户问题（场景绝对限定：仅解答预约/教室/个人记录相关问题） */
    private String question;
}
