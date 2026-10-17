package com.example.reservation.ai.support;

import java.io.IOException;

/**
 * 所有 AI SSE 输出端口的公共契约（R12/R13-5）。
 *
 * <p>仅收敛「致命错误通知」一个方法，使 SSE 生命周期工具 {@link SseStreams}
 * 能在超时/线程池拒绝/任务异常时统一向任意一种 sink 补发 error 帧；
 * 具体业务事件由子接口（{@link ChatStreamSink}、{@link StructuredStreamSink}）定义。
 */
public interface AiStreamSink {

    /**
     * 流已建立后的致命错误（如 70s 超时、线程池拒绝后的兜底通知）；
     * 客户端已断开时实现抛 IOException，调用方静默吞掉。
     */
    void error(String message) throws IOException;
}
