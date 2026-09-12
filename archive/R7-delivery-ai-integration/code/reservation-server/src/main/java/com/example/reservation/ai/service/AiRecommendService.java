package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiRecommendVO;

/**
 * 智能教室推荐服务（R7，POST /api/ai/recommend）
 * 基于用户历史预约习惯（时段/楼栋/类型/人数）+ 实时空闲状态，推荐 Top3 教室并附理由
 * 只读不写：AI 仅查询数据、给出建议，不执行任何业务操作
 *
 * @author reservation-team
 */
public interface AiRecommendService {

    /**
     * 智能推荐 Top3 教室
     *
     * @param userId 用户 ID（依据该用户历史预约习惯推荐）
     * @return 推荐结果（ai.enable=false 时返回 enabled=false 的友好提示，不报 500）
     */
    AiRecommendVO recommend(Long userId);
}
