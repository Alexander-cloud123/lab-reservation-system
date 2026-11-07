package com.example.reservation.ai.support;

import java.io.IOException;

/**
 * 流式问答事件输出端口（R12）
 *
 * service 只面向该接口编排事件顺序，不依赖 Servlet/SSE API：
 * 生产实现 {@link SseChatStreamSink} 写 SSE，单测用录制事件的假实现。
 * 事件契约：meta（仅 1 个，先于 delta）→ delta* → done；中途降级可用 reset 全量替换；致命错误用 error。
 *
 * @author hyr
 */
public interface ChatStreamSink extends AiStreamSink {

    /** 元信息：AI 是否启用、是否降级、降级原因文案（非降级 message=null） */
    void meta(boolean enabled, boolean degraded, String message) throws IOException;

    /** 文本片段（前端直接追加） */
    void delta(String text) throws IOException;

    /** 上游中途失败后的全量替换：前端必须丢弃此前片段，展示完整本地降级答案。 */
    void reset(String text, String message) throws IOException;

    /** 正常收尾：finishReason=stop/fallback/disabled 等 */
    void done(String finishReason) throws IOException;
}
