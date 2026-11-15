package com.example.reservation.ai.support;

import com.example.reservation.ai.dto.AiStreamReasoning;
import com.example.reservation.ai.dto.AiStreamThinking;

import java.io.IOException;

/**
 * 流式问答事件输出端口（R12）
 *
 * service 只面向该接口编排事件顺序，不依赖 Servlet/SSE API：
 * 生产实现 {@link SseChatStreamSink} 写 SSE，单测用录制事件的假实现。
 * 事件契约：meta（仅 1 个，先于一切 delta）→ delta* → done；中途降级可用 reset 全量替换；致命错误用 error。
 * R14 起新增 thinking（处理步骤与耗时）与 reasoning（模型推理透传）两类事件：
 * 二者都只描述过程、不承载答案；thinking 允许出现在 meta 之前（服务端在请求入口即开始计时与步骤），
 * reasoning 只在 generate 进行中出现。两者均为 default 方法，旧实现/测试桩无需改动即可编译。
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

    /**
     * 处理步骤事件（运行中 / 完成 / 失败，含该步真实耗时）。
     * 默认空实现：不支持思考过程的调用方（如只关心答案的旧测试桩）忽略即可。
     */
    default void thinking(AiStreamThinking payload) throws IOException {
    }

    /**
     * 模型真实推理片段（仅上游返回 reasoning_content 时出现）。
     * 默认空实现：不透传推理的调用方忽略即可。
     */
    default void reasoning(AiStreamReasoning payload) throws IOException {
    }
}
