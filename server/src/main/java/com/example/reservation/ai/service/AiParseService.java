package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.support.StructuredStreamSink;

/**
 * 自然语言预约解析服务（R7，POST /api/ai/parse-reservation；R13-5 新增 ?stream=true SSE）
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

    /**
     * 流式解析入参校验（R13-5）：必须在 SSE 建立前（请求线程）执行，
     * 不合法抛 BusinessException，由全局异常处理器回 Result JSON（而不是 SSE error 帧）。
     */
    void validateParseInput(String text);

    /**
     * 流式解析预约需求（R13-5）：meta → phase（无缓存，必发）→ result（同构 VO，含 error 识别失败约定）→ done。
     *
     * @param text   已通过 {@link #validateParseInput} 的需求文本
     * @param userId 请求线程捕获的登录用户 ID（异步线程无 UserContext）
     * @param sink   结构化事件输出端口
     */
    void streamParse(String text, Long userId, StructuredStreamSink<AiParseVO> sink);
}
