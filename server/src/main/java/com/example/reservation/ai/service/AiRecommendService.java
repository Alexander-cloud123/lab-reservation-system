package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiRecommendVO;
import com.example.reservation.ai.support.StructuredStreamSink;

/**
 * 智能教室推荐服务（R7，POST /api/ai/recommend；R13-5 新增 ?stream=true SSE）
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

    /**
     * 智能推荐（R13-5，SSE 结构化流式）：事件序列 meta → phase（缓存未命中时）→ result（同构 VO）→ done。
     * 与 {@link #recommend(Long)} 同一套候选构建/降级/缓存逻辑，仅结果经 SSE 逐事件下发。
     *
     * @param userId 请求线程捕获的登录用户 ID（异步线程无 UserContext）
     * @param sink   结构化事件输出端口
     */
    void streamRecommend(Long userId, StructuredStreamSink<AiRecommendVO> sink);
}
