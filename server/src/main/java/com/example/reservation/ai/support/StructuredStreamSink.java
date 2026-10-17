package com.example.reservation.ai.support;

import java.io.IOException;

/**
 * 结构化结果流式输出端口（R13-5：recommend / parse / compliance 的 ?stream=true）。
 *
 * <p>service 只面向该接口编排事件顺序，不依赖 Servlet/SSE API：
 * 生产实现 {@link SseStructuredStreamSink} 写 SSE，单测用录制事件的假实现。
 * 事件契约（每流固定序列）：
 * <pre>
 * meta（仅 1 个，先于一切业务事件）
 *   → phase（0 或 1 个：仅缓存未命中、真正调用上游前发出）
 *   → result（与非流式 data 同构 VO；关闭态同样经 result 下发 enabled=false）
 *   → done
 * 致命错误用 error（超时/线程池拒绝/任务异常）。
 * </pre>
 *
 * @param <T> 结构化结果 VO 类型
 */
public interface StructuredStreamSink<T> extends AiStreamSink {

    /** 元信息：AI 是否启用、是否降级、提示文案（关闭态 enabled=false/degraded=true/message=关闭原因） */
    void meta(boolean enabled, boolean degraded, String message) throws IOException;

    /** 进度事件：phase=阶段 code，text=展示文案（缓存命中时不发送） */
    void phase(String phase, String text) throws IOException;

    /** 结构化结果（线格式与非流式接口 data 同构 VO），正常/降级/识别失败均经此事件落地 */
    void result(T vo) throws IOException;

    /** 正常收尾（无载荷） */
    void done() throws IOException;
}
