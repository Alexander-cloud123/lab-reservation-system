package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiPlanVO;

/**
 * 对话内预约规划（R15-1）——只生成「可确认方案」，不写任何业务数据。
 *
 * <p>流程：自然语言解析 → 检索指定日期/时段的可用教室 → 排除冲突与容量不足 →
 * 排序取前 3 间 + 统计更多可用数。结果由前端渲染成方案卡，用户点「去预约」
 * 进入既有确认弹窗手动提交（AI 只读不写，见需求文档 1.6 AI 业务规则）。
 *
 * @author hyr
 */
public interface AiBookingPlanService {

    /**
     * 生成预约方案
     *
     * @param userId 当前用户 ID（用于个人偏好加权，可为空）
     * @param text   用户原始问题（一句话，≤200 字）
     * @return 方案（解析失败 / 无候选时由 clarify 说明，candidates 为空列表）
     */
    AiPlanVO plan(Long userId, String text);
}
