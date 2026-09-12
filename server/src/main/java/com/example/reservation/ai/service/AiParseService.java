package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiParseVO;

/**
 * 自然语言预约解析服务（R7，POST /api/ai/parse-reservation）
 * 口语化描述 → 结构化预约参数（date/startTime/endTime/capacity/roomType/purpose）
 * 识别失败返回 error=「无法解析」，由前端引导用户重新描述或手动填写
 *
 * @author reservation-team
 */
public interface AiParseService {

    /**
     * 解析预约需求文本
     *
     * @param text 口语化预约需求描述
     * @return 结构化解析结果（ai.enable=false 时返回 enabled=false 的友好提示）
     */
    AiParseVO parse(String text);
}
