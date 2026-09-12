package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 智能教室推荐请求（POST /api/ai/recommend，需求文档 2.5）
 *
 * @author reservation-team
 */
@Data
public class AiRecommendRequest {

    /** 用户 ID（推荐依据：该用户历史预约习惯） */
    private Long userId;
}
