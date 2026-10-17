package com.example.reservation.ai.service;

import com.example.reservation.ai.dto.AiChatHistoryMessage;
import com.example.reservation.ai.dto.AiChatVO;
import com.example.reservation.ai.support.ChatStreamSink;

import java.util.List;

/**
 * 预约智能问答服务（R7，POST /api/ai/chat；R12 增加 ?stream=true 流式；R13-3 增加多轮 history）
 * 场景绝对限定（需求文档 1.4 AI 业务规则 1）：仅解答预约/教室/个人记录相关问题，
 * 无关问题统一返回预设话术；个人数据通过检索增强注入上下文
 *
 * @author reservation-team
 */
public interface AiChatService {

    /**
     * 场景限定问答（非流式）
     *
     * @param question 用户问题
     * @param history  会话内历史消息（可选，null/空退化为单轮；后端二次裁剪）
     * @return 助手回答（无关问题返回预设话术；ai.enable=false 时返回 enabled=false 的友好提示）
     */
    AiChatVO chat(String question, List<AiChatHistoryMessage> history);

    /**
     * 问答入参校验（controller 在请求线程调用：SSE 建立前校验失败可走全局异常 JSON；
     * 与非流式 chat() 的校验规则完全一致）
     *
     * @throws com.example.reservation.common.BusinessException 空问题 / 超长
     */
    void validateQuestion(String question);

    /**
     * 流式问答（POST /api/ai/chat?stream=true）
     *
     * @param userId   当前登录用户 ID（controller 在请求线程从 UserContext 捕获后传入，异步线程无 ThreadLocal）
     * @param question 用户问题（调用前须先经 {@link #validateQuestion}）
     * @param history  会话内历史消息（可选，null/空退化为单轮；后端二次裁剪）
     * @param sink     事件输出端口（meta → delta* → done/error）
     */
    void streamChat(Long userId, String question, List<AiChatHistoryMessage> history, ChatStreamSink sink);
}
