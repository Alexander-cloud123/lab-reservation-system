package com.example.reservation.ai.dto;

import com.example.reservation.ai.config.AiConstants;
import lombok.Data;

import java.util.List;

/**
 * 智能教室推荐结果 VO（POST /api/ai/recommend）
 * enabled=false 表示 AI 未启用（双开关任一关闭），前端据此隐藏/禁用 AI 入口
 *
 * @author reservation-team
 */
@Data
public class AiRecommendVO {

    /** AI 是否启用（false 时前端隐藏 AI 入口，系统退化为纯预约系统） */
    private boolean enabled;

    /** 提示信息（启用时为空；关闭时为友好提示，不报 500） */
    private String message;

    /** 推荐基准日期（yyyy-MM-dd，默认明天） */
    private String date;

    /** 推荐教室列表（Top3，含推荐理由） */
    private List<AiRecommendItemVO> recommendations;

    /** AI 关闭时的友好返回（前端据此隐藏/禁用入口） */
    public static AiRecommendVO disabled() {
        AiRecommendVO vo = new AiRecommendVO();
        vo.setEnabled(false);
        vo.setMessage(AiConstants.AI_DISABLED_MESSAGE);
        return vo;
    }
}
