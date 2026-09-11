package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 自然语言预约解析请求（POST /api/ai/parse-reservation，需求文档 2.5）
 *
 * @author reservation-team
 */
@Data
public class AiParseRequest {

    /** 口语化预约需求描述（如：明天下午2点到4点 40人 机房） */
    private String text;
}
