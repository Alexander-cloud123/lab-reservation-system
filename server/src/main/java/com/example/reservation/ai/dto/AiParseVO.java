package com.example.reservation.ai.dto;

import com.example.reservation.ai.config.AiConstants;
import lombok.Data;

/**
 * 自然语言预约解析结果 VO（POST /api/ai/parse-reservation）
 * 结构化参数与 Prompt 模板输出约定一致：date/startTime/endTime/capacity/roomType/purpose
 * 识别失败时 error 字段返回「无法解析」
 *
 * @author reservation-team
 */
@Data
public class AiParseVO {

    /** AI 是否启用（false 时前端隐藏 AI 入口） */
    private boolean enabled;

    /** 提示信息（关闭时为友好提示） */
    private String message;

    /** 预约日期（yyyy-MM-dd） */
    private String date;

    /** 开始时间（HH:mm） */
    private String startTime;

    /** 结束时间（HH:mm） */
    private String endTime;

    /** 容纳人数 */
    private Integer capacity;

    /** 教室类型文案：普通教室 | 实验室 | 机房 | null */
    private String roomType;

    /** 预约用途 */
    private String purpose;

    /** 解析失败标志（识别失败返回「无法解析」，前端提示用户重新描述） */
    private String error;

    /** AI 关闭时的友好返回 */
    public static AiParseVO disabled() {
        AiParseVO vo = new AiParseVO();
        vo.setEnabled(false);
        vo.setMessage(AiConstants.AI_DISABLED_MESSAGE);
        return vo;
    }
}
