package com.example.reservation.ai.support;

import java.io.IOException;

/**
 * 流式问答事件输出端口（R12）
 *
 * service 只面向该接口编排事件顺序，不依赖 Servlet/SSE API：
 * 生产实现 {@link SseChatStreamSink} 写 SSE，单测用录制事件的假实现。
 * 事件契约：meta（仅 1 个，先于 delta）→ delta* → done；致命错误用 error。
 *
 * @author reservation-team
 */
public interface ChatStreamSink {

    /** 元信息：AI 是否启用、是否降级、降级原因文案（非降级 message=null） */
    void meta(boolean enabled, boolean degraded, String message) throws IOException;

    /** 文本片段（前端直接追加） */
    void delta(String text) throws IOException;

    /** 正常收尾：finishReason=stop/fallback/disabled 等 */
    void done(String finishReason) throws IOException;

    /** 流已建立后的致命错误（如线程池拒绝后的兜底通知） */
    void error(String message) throws IOException;
}
